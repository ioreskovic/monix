/*
 * Copyright (c) 2014-2022 Monix Contributors.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.reactive.internal.operators

import monix.execution.Ack.Continue
import monix.execution.{ Ack, Cancelable }
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

private[reactive] final class IntersperseObservable[+A](
  source: Observable[A],
  start: Option[A],
  separator: A,
  end: Option[A]
) extends Observable[A] { self =>

  override def unsafeSubscribeFn(out: Subscriber[A]): Cancelable = {
    val upstream = source.unsafeSubscribeFn(new Subscriber[A] {
      implicit val scheduler = out.scheduler

      private[this] var atLeastOne = false
      private[this] var downstreamAck = Continue: Future[Ack]

      override def onNext(elem: A): Future[Ack] = {
        downstreamAck = if (!atLeastOne) {
          atLeastOne = true
          start.map(out.onNext).getOrElse(Continue).syncFlatMap {
            case Continue => out.onNext(elem)
            case ack => ack
          }
        } else {
          out.onNext(separator).syncFlatMap {
            case Continue => out.onNext(elem)
            case ack => ack
          }
        }
        downstreamAck
      }

      def onError(ex: Throwable) = {
        downstreamAck.syncOnContinue(out.onError(ex))
        ()
      }

      def onComplete() = {
        downstreamAck.syncOnContinue {
          if (atLeastOne && end.nonEmpty) out.onNext(end.get)
          out.onComplete()
        }
        ()
      }
    })

    Cancelable { () =>
      upstream.cancel()
    }
  }
}
