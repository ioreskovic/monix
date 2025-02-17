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

package monix.reactive.internal.builders

import cats.effect.IO
import minitest.TestSuite
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.internal.Platform
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import scala.util.Failure
import scala.concurrent.duration.MILLISECONDS

object AsyncStateActionObservableSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(s: TestScheduler): Unit = {
    assert(s.state.tasks.isEmpty, "TestScheduler should have no pending tasks")
  }

  test("first execution can be sync") { implicit s =>
    var received = 0
    Observable
      .fromAsyncStateAction(intNow)(s.clockMonotonic(MILLISECONDS))
      .take(1L)
      .subscribe { x =>
        received += 1; Continue
      }

    assertEquals(received, 1)
  }

  test("should do synchronous execution in batches") { implicit s =>
    var received = 0
    Observable
      .fromAsyncStateAction(intNow)(s.clockMonotonic(MILLISECONDS))
      .take(Platform.recommendedBatchSize.toLong * 3)
      .subscribe { x =>
        received += 1; Continue
      }

    assertEquals(received, Platform.recommendedBatchSize / 2)
    s.tickOne()
    assertEquals(received, Platform.recommendedBatchSize - 1)
    s.tick()
    assertEquals(received, Platform.recommendedBatchSize * 3)
  }

  test("should do async execution") { implicit s =>
    var received = 0
    Observable
      .fromAsyncStateAction(intAsync)(s.clockMonotonic(MILLISECONDS))
      .take(Platform.recommendedBatchSize.toLong * 2)
      .subscribe { x =>
        received += 1; Continue
      }

    s.tick()
    assertEquals(received, Platform.recommendedBatchSize * 2)
  }

  test("fromAsyncStateAction should be cancelable") { implicit s =>
    var wasCompleted = false
    var sum = 0

    val cancelable = Observable
      .fromAsyncStateAction(intNow)(s.clockMonotonic(MILLISECONDS))
      .unsafeSubscribeFn(new Subscriber[Int] {
        implicit val scheduler = s
        def onNext(elem: Int) = {
          sum += 1
          Continue
        }

        def onComplete() = wasCompleted = true
        def onError(ex: Throwable) = wasCompleted = true
      })

    cancelable.cancel()
    s.tick()

    assertEquals(sum, s.executionModel.recommendedBatchSize / 2)
    assert(!wasCompleted)
  }

  test("should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = Observable.fromAsyncStateAction(intError(ex))(s.clockMonotonic(MILLISECONDS)).runAsyncGetFirst

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("should respect the ExecutionModel") { scheduler =>
    implicit val s = scheduler.withExecutionModel(AlwaysAsyncExecution)

    var received = 0
    val cancelable = Observable
      .fromAsyncStateAction(intNow)(s.clockMonotonic(MILLISECONDS))
      .subscribe { _ =>
        received += 1; Continue
      }

    assertEquals(received, 0)
    s.tickOne(); s.tickOne()
    assertEquals(received, 1)
    s.tickOne(); s.tickOne()
    assertEquals(received, 2)

    cancelable.cancel(); s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should do async execution with cats.effect.IO") { implicit s =>
    var received = 0
    Observable
      .fromAsyncStateActionF(intAsyncIO)(s.clockMonotonic(MILLISECONDS))
      .take(Platform.recommendedBatchSize.toLong * 2)
      .subscribe { _ =>
        received += 1; Continue
      }

    s.tick()
    assertEquals(received, Platform.recommendedBatchSize * 2)
  }

  def intAsyncIO(seed: Long): IO[(Int, Long)] = IO.async(cb => cb(Right(int(seed))))
  def intAsync(seed: Long) = Task.evalAsync(int(seed))
  def intNow(seed: Long) = Task.now(int(seed))
  def intError(ex: Throwable)(seed: Long) = Task.raiseError[(Int, Long)](ex)

  def int(seed: Long): (Int, Long) = {
    // `&` is bitwise AND. We use the current seed to generate a new seed.
    val newSeed = (seed * 0x5deece66dL + 0xbL) & 0xffffffffffffL
    // The next state, which is an `RNG` instance created from the new seed.
    val nextRNG = newSeed
    // `>>>` is right binary shift with zero fill. The value `n` is our new pseudo-random integer.
    val n = (newSeed >>> 16).toInt
    // The return value is a tuple containing both a pseudo-random integer and the next `RNG` state.
    (n, nextRNG)
  }
}
