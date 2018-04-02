/*
 * Copyright (C) 2017  Fluence Labs Limited
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fluence.node

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, SequenceInputStream}
import java.nio.ByteBuffer

import cats.data.EitherT
import cats.effect._
import com.google.protobuf.{ByteString, NioByteString}
import fluence.kad.grpc.KademliaGrpc.Kademlia
import fluence.kad.grpc.{KademliaGrpc, LookupRequest}
import fluence.transport.grpc.server.GrpcServer
import io.grpc
import io.grpc._
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.internal.IoUtils
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import org.http4s.EntityEncoder._
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.Promise
import scala.util.Try

case class Result(resp: Any, headers: Metadata, status: io.grpc.Status, trailers: Metadata)

class HttpProxyHttp4s(server: GrpcServer) extends slogging.LazyLogging {

  val list: List[ServerServiceDefinition] = Nil

  val services = server.serverRef.get().getImmutableServices.asScala

  val pp = InProcessServerBuilder.forName("in-process")
  services.foreach(s ⇒ pp.addService(s))
  pp.build().start()

  val ss = pp.build()

  val chs = InProcessChannelBuilder.forName("in-process").build()

  def listener[T](
    onMessagePr: Promise[T],
    onHeadersPr: Promise[Metadata],
    onClosePr: Promise[(io.grpc.Status, Metadata)]
  ) = new ClientCall.Listener[T] {
    override def onHeaders(headers: Metadata): Unit = {
      super.onHeaders(headers)
      onHeadersPr.trySuccess(headers)
      logger.error("HEADERS === " + headers)
    }

    override def onClose(status: io.grpc.Status, trailers: Metadata): Unit = {
      logger.error("ON CLOSE === " + status + "   " + trailers)
      onClosePr.trySuccess((status, trailers))
      super.onClose(status, trailers)
    }

    override def onMessage(message: T): Unit = {
      logger.error("ON MESSAGE === " + message)
      onMessagePr.trySuccess(message)
      super.onMessage(message)
    }

    override def onReady(): Unit = {
      logger.error("ON READY === ")
      super.onReady()
    }
  }

  val helloWorldService = HttpService[IO] {
    case req @ GET ⇒
      logger.error(req.toString())
      logger.error(s"Hello, get.")
      Ok(s"Hello, get.")
    case req @ POST -> Root / service / method ⇒
      logger.error(req.toString())
      logger.error(s"Hello, post.")
      logger.error(s"service is === " + service)
      logger.error(s"method is === " + method)
      val stringReq = req.as[Array[Byte]].unsafeRunSync()
      logger.error(s"Req as string === " + stringReq.mkString(","))

      def getMd(): Option[ServerMethodDefinition[Any, Any]] = {
        for {
          sd ← services.find(_.getServiceDescriptor.getName == service)
          _ = logger.error("SERVICE DESCRIPTION == " + sd)
          m ← Option(sd.getMethod(service + "/" + method).asInstanceOf[ServerMethodDefinition[Any, Any]])
        } yield m
      }

      val methodDescriptor = for {
        m ← getMd()
      } yield {
        logger.error("CALL THIS!!!")

        val onMessagePr = Promise[Any]
        val onHeadersPr = Promise[Metadata]
        val onClosePr = Promise[(io.grpc.Status, Metadata)]
        Try {
          val md = m.getMethodDescriptor
          val marshalledReq =
            md.parseRequest(new ByteArrayInputStream(stringReq.slice(5, stringReq.length)))

          val metadata = new Metadata()
          val call = chs.newCall(md, CallOptions.DEFAULT)

          call.start(listener(onMessagePr, onHeadersPr, onClosePr), metadata)
          call.sendMessage(marshalledReq)
          call.request(1)
          call.halfClose()

          onMessagePr.future.zip(onHeadersPr.future).zip(onClosePr.future).map {
            case ((res, headers), (status, trailers)) ⇒
              Result(res, headers, status, trailers)
          }
        }.recover {
          case e: Throwable ⇒
            e.printStackTrace()
            logger.error("SOME ERROR", e)
            onMessagePr.tryFailure(e)
            onMessagePr.future.zip(onHeadersPr.future).zip(onClosePr.future).map {
              case ((res, headers), (status, trailers)) ⇒
                Result(res, headers, status, trailers)
            }
        }.get
      }

      val a = for {
        res ← IO.fromFuture(IO(methodDescriptor.get)).attempt
        stat ← res match {
          case Right(r) ⇒
            println("RESPONSE === " + r)
            val m = getMd().get
            println("11111")
            val md = m.getMethodDescriptor
            println("222222")
            val marshalledResp = md.getResponseMarshaller.stream(r.resp)

            val startStream = new ByteArrayInputStream(Array[Byte](0, 0, 0, 1, 35))

            val streams: InputStream = new SequenceInputStream(startStream, marshalledResp)

            println("333333")

            logger.error("We return some")
            val is = fs2.io.readInputStream(IO(streams), 100000, true)

            println("44444444")

            Ok("")
              .map(_.withBodyStream(is))
              .map(
                _.replaceAllHeaders(
                  req.headers
                    .filter(
                      p ⇒ p.name != CaseInsensitiveString("Host") && p.name != CaseInsensitiveString("Connection")
                    )
                    .put(Header("grpc-status", r.status.getCode.value().toString))
                    .put(Header("grpc-message", Option(r.status.getDescription).getOrElse("")))
                )
              )
          case Left(e) ⇒
            e.printStackTrace()
            InternalServerError(e.getLocalizedMessage)
        }
      } yield {

        stat
      }

      for {
        respE ← a.attempt
      } yield {
        respE match {
          case Left(e) ⇒
            e.printStackTrace()
          case _ ⇒
        }
      }
      a

    case req ⇒
      logger.error(req.toString())
      logger.error("hi rec")
      Ok("Hi req")
  }

}

object HttpProxyHttp4s {

  def builder(server: GrpcServer): IO[Server[IO]] = {
    val s = new HttpProxyHttp4s(server)

    BlazeBuilder[IO].bindHttp(8090).mountService(s.helloWorldService, "/").start
  }
}
