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

package fluence.grpc.proxy

import cats.effect.IO
import cats.~>
import com.google.protobuf.ByteString
import fluence.grpc.proxy.test.TestServiceGrpc.TestService
import fluence.grpc.proxy.test.{TestMessage, TestRequest, TestResponse, TestServiceGrpc}
import fluence.proxy.grpc.WebsocketMessage
import fluence.proxy.grpc.WebsocketMessage.Reply
import fluence.proxy.grpc.WebsocketMessage.Reply.ProtoMessage
import io.grpc.MethodDescriptor
import io.grpc.stub.StreamObserver
import org.scalatest.{Matchers, WordSpec}
import scalapb.GeneratedMessage
import slogging.{LogLevel, LoggerConfig, PrintLoggerFactory}
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.Random

//TODO move test in proxy module and rewrite with synthetic grpc services
class ProxyUnaryCallSpec extends WordSpec with Matchers {

  LoggerConfig.factory = PrintLoggerFactory()
  LoggerConfig.level = LogLevel.DEBUG

  "proxy" should {

    val respChecker = "resp"
    val respCheckerBytes = ByteString.copyFrom(Array[Byte](5, 6, 7, 8))

    val RPC = {
      new TestService {
        override def test(request: TestRequest): Future[TestResponse] = {
          println("REQUEST === " + request)
          val resp = request.message.map { r ⇒
            r.copy(str = r.str + respChecker, listStr = r.listStr :+ respChecker, r.byteArray.concat(respCheckerBytes))
          }
          Future(TestResponse(resp))
        }

        override def testCount(responseObserver: StreamObserver[TestResponse]): StreamObserver[TestRequest] = {
          new StreamObserver[TestRequest] {
            override def onNext(value: TestRequest): Unit = {
              println(s"SERVER ON NEXT $value")
              if (!value.close) {
                val resp = TestResponse(value.message.map(m ⇒ m.copy(counter = m.counter + 1)))
                responseObserver.onNext(resp)
              } else {
                responseObserver.onCompleted()
              }
            }

            override def onError(t: Throwable): Unit = {
              println(s"ON ERROR:")
              t.printStackTrace()
            }

            override def onCompleted(): Unit = println("ON COMPLETED")
          }
        }
      }
    }

    val service = TestServiceGrpc.bindService(RPC, scala.concurrent.ExecutionContext.global)

    implicit def runFuture: Future ~> IO = new (Future ~> IO) {
      override def apply[A](fa: Future[A]): IO[A] = IO.fromFuture(IO(fa))
    }

    def generateMessage[Req <: GeneratedMessage, Resp](
      streamId: Long,
      req: Req,
      descriptor: MethodDescriptor[Req, Resp]
    ): WebsocketMessage = {
      val splitted = descriptor.getFullMethodName.split("/").toList

      WebsocketMessage(splitted(0), splitted(1), streamId, ProtoMessage(req.toByteString))
    }

    "work with unary calls" in {
      val inProcessGrpc = InProcessGrpc.build("in-process", List(service)).unsafeRunSync()

      val proxyGrpc = new ProxyGrpc[IO](inProcessGrpc)

      val str = "test"
      val listStr = Seq("test1", "test2")
      val byteArray = ByteString.copyFrom(Array[Byte](1, 2, 3, 4, 5))

      val testMessage =
        generateMessage(123123L, TestRequest(Some(TestMessage(str, listStr, byteArray))), TestServiceGrpc.METHOD_TEST)

      val testResp = Await
        .result(
          proxyGrpc
            .handleMessage(
              testMessage.service,
              testMessage.method,
              testMessage.streamId,
              ProxyGrpc.replyConverter(testMessage.reply)
            )
            .unsafeRunSync()
            .runAsyncGetLast,
          5.seconds
        )
        .get

      val respBytes = testResp match {
        case ResponseArrayByte(b) ⇒ b
        case _ ⇒ throw new RuntimeException("error")
      }

      val resp = TestRequest.parseFrom(respBytes).message.get
      resp.str shouldBe str + "resp"
      resp.listStr shouldBe listStr :+ "resp"
      resp.byteArray shouldBe byteArray.concat(respCheckerBytes)

      inProcessGrpc.close().unsafeRunSync()
    }

    "work with bidi streams" in {

      val inProcessGrpc = InProcessGrpc.build("in-process", List(service)).unsafeRunSync()

      val proxyGrpc = new ProxyGrpc[IO](inProcessGrpc)

      try {
        val str = "test"
        val listStr = Seq("test1", "test2")
        val byteArray = ByteString.copyFrom(Array[Byte](1, 2, 3, 4, 5))

        def sendMessage(message: WebsocketMessage): Observable[Response] = {

          val testRespF = proxyGrpc
            .handleMessage(
              message.service,
              message.method,
              message.streamId,
              ProxyGrpc.replyConverter(message.reply)
            )
            .unsafeRunSync()

          testRespF.map { r ⇒
            println("RESPONSE IN SENDMESSAGe === " + r)
            r
          }
        }

        val testMessage =
          generateMessage(
            123L,
            TestRequest(Some(TestMessage(str, listStr, byteArray, 1))),
            TestServiceGrpc.METHOD_TEST_COUNT
          )

        val testRespF = sendMessage(testMessage)

        val m = testRespF.collect {
          case a ⇒
            println("A === " + a)
            val resp = a match {
              case ResponseArrayByte(b) ⇒
                val resp = TestRequest.parseFrom(b)
                println("COUNTER === " + resp.message.get.counter)
                resp.message.get.counter match {
                  case 10 ⇒
                    val msgClose = generateMessage(
                      123L,
                      TestRequest(Some(TestMessage(str, listStr, byteArray, 10)), close = true),
                      TestServiceGrpc.METHOD_TEST_COUNT
                    )
                    sendMessage(msgClose)
                    "GO"
                  case c ⇒
                    val testMessage =
                      generateMessage(
                        123L,
                        TestRequest(Some(TestMessage(str, listStr, byteArray, c))),
                        TestServiceGrpc.METHOD_TEST_COUNT
                      )
                    sendMessage(testMessage)
                    "GO"

                }
              case NoResponse ⇒ "STOP"
            }
        }.foreach(a ⇒ println(a))

        Await.ready(m, 10.seconds)
        println("promise ended")

      } catch {
        case e: Throwable ⇒
          throw e
      } finally {
        println("???")
        try {
          inProcessGrpc.unsafeClose().unsafeRunSync()
          println("!!!")
        } catch {
          case e: Throwable ⇒ e.printStackTrace()
        }
      }
    }

    "raise error if the proxy was closed" in {
      val inProcessGrpc = InProcessGrpc.build("in-process", List(service)).unsafeRunSync()

      val proxyGrpc = new ProxyGrpc[IO](inProcessGrpc)

      inProcessGrpc.unsafeClose().unsafeRunSync()

      val testMessage =
        generateMessage(1111L, TestRequest(Some(TestMessage())), TestServiceGrpc.METHOD_TEST)

      the[RuntimeException] thrownBy {
        proxyGrpc
          .handleMessage(testMessage.service, testMessage.method, 1L, ProxyGrpc.replyConverter(testMessage.reply))
          .unsafeRunSync()
      }

      inProcessGrpc.unsafeClose().unsafeRunSync()
    }

    "raise error if no method or service descriptor in proxy" in {
      val inProcessGrpc = InProcessGrpc.build("in-process", List(service)).unsafeRunSync()

      val proxyGrpc = new ProxyGrpc[IO](inProcessGrpc)

      val testMessage =
        generateMessage(555L, TestRequest(Some(TestMessage())), TestServiceGrpc.METHOD_TEST)

      the[RuntimeException] thrownBy {
        proxyGrpc
          .handleMessage("rndservice", testMessage.method, 1L, ProxyGrpc.replyConverter(testMessage.reply))
          .unsafeRunSync()
      }

      the[RuntimeException] thrownBy {
        proxyGrpc
          .handleMessage(testMessage.service, "rndmethod", 1L, ProxyGrpc.replyConverter(testMessage.reply))
          .unsafeRunSync()
      }

      inProcessGrpc.unsafeClose().unsafeRunSync()
    }
  }
}
