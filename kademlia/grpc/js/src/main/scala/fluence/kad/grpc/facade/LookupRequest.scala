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

package fluence.kad.grpc.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("./generated/grpc_pb", "LookupRequest")
class LookupRequest() extends js.Object {

  def setKey(key: Uint8Array): Unit = js.native
  def setNumberofnodes(num: Int): Unit = js.native
  def getKey(): Uint8Array = js.native
  def getNumberofnodes(): Int = js.native
}

object LookupRequest {
  implicit class LookupRequestOps(req: LookupRequest) {
    def key: Uint8Array = req.getKey()
    def numberOfNodes: Int = req.getNumberofnodes()
  }

  def apply(key: Uint8Array, numberOfNodes: Int): LookupRequest = {
    val req = new LookupRequest()
    req.setKey(key)
    req.setNumberofnodes(numberOfNodes)
    req
  }
}
