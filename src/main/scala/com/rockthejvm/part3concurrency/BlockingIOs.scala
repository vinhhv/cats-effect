package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import com.rockthejvm.utils.*

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

object BlockingIOs extends IOApp.Simple {

  val someSleeps = for {
    _ <- IO.sleep(1.second).debugs // SEMANTIC BLOCKING
    _ <- IO.sleep(1.second).debugs
  } yield ()

  // really blocking IOs
  val aBlockingIO = IO.blocking {
    Thread.sleep(1000)
    println(s"[${Thread.currentThread().getName}] computed a blocking code")
    42
  } // will evaluate on a thread from ANOTHER thread pool specific for blocking calls

  // yielding
  val iosOnManyThreads = for {
    _ <- IO("first").debugs
    _ <- IO.cede             // a signal to yield control over the thread - equivalent to IO.shift
    _ <- IO("second").debugs // the rest of this effect may run on another thread (not necessarily)
    _ <- IO.cede
    _ <- IO("third").debugs
  } yield ()

  /*
    - blocking calls & IO.sleep implement SEMANTIC BLOCKING and yield control over the calling thread automatically
   */

  def testThousandEffectsSwitch(): IO[Int] = {
    val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))
    (1 to 1000).map(IO.pure).reduce(_.debugs >> IO.cede >> _.debugs).evalOn(ec)
  }

  override def run: IO[Unit] = testThousandEffectsSwitch().void
}
