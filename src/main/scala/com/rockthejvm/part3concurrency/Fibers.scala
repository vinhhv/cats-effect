package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Fiber, IO, IOApp}
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object Fibers extends IOApp.Simple {

  val meaningOfLife = IO.pure(42)
  val favLang       = IO.pure("Scala")

  def sameThreadIOs(): IO[Unit] = for {
    _ <- meaningOfLife.debugs
    _ <- favLang.debugs
  } yield ()

  // introduce the Fiber
  def createFiber: Fiber[IO, Throwable, String] = ??? // almost impossible to create fibers manually

  // the fiber is not actually started, but the fiber allocation is wrapped in another effect
  val aFiber: IO[Fiber[IO, Throwable, Int]] = meaningOfLife.debugs.start

  def differentThreadIOs(): IO[Unit] = for {
    _ <- aFiber
    _ <- favLang.debugs
  } yield ()

  // joining a fiber
  def runOnSomeOtherThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] = for {
    fib    <- io.start
    result <- fib.join // an effect which waits for the fiber to terminate
  } yield result
  /*
    IO[ResultType of fib.join]
    fib.join = Outcome[IO, Throwable, A]

    possible outcomes:
    - success with an IO
    - failure with an exception
    - cancelled
   */

  val someIOOnAnotherThread = runOnSomeOtherThread(meaningOfLife)
  val someResultFromAnotherThread = someIOOnAnotherThread.flatMap {
    case Succeeded(effect) => effect
    case Errored(e)        => IO(0)
    case Canceled()        => IO(0)
  }

  def throwOnAnotherThread() = for {
    fib    <- IO.raiseError[Int](new RuntimeException("no number for you")).start
    result <- fib.join
  } yield result

  def testCancel() = {
    val task = IO("starting").debugs >> IO.sleep(1.second) >> IO("done").debugs
    // onCancel is a "finalizer", allowing you to free up resources in case you get cancelled
    val taskWithCancellationHandler = task.onCancel(IO("I'm being cancelled").debugs.void)

    for {
      fib    <- taskWithCancellationHandler.start // on a separate thread
      _      <- IO.sleep(500.millis) >> IO("cancelling").debugs
      _      <- fib.cancel
      result <- fib.join
    } yield result
  }

  /**
   * Exercises:
   *  1. Write a function that runs an IO on another thread, and, depending on the result of the fiber
   *    - return the result in an IO
   *    - if errored or cancelled, return a failed IO
   *
   * 2. Write a function that takes two IOs, runs them on different fibers and returns an IO with a tuple containing both results.
   *    - if both IOs complete successfully, tuple their results
   *    - if the first IO returns an error, raise that error (ignoring the second IO's result/error)
   *    - if the first IO doesn't error but second IO returns an error, raise that error
   *    - if one (or both) canceled, raise a RuntimeException
   *
   * 3. Write a function that adds a timeout to an IO:
   *    - IO runs on a fiber
   *    - if the timeout duration passes, then the fiber is canceled
   *    - the method returns an IO[A] which contains
   *      - the original value if the computation is successful before the timeout signal
   *      - the exception if the computation is failed before the timeout signal
   *      - a RuntimeException if it times out (i.e. cancelled by the timeout)
   */
  // 1
  def processResultsFromFiber[A](io: IO[A]): IO[A] =
    for {
      fib     <- io.debugs.start
      outcome <- fib.join
      result <- outcome match
        case Succeeded(fa) => fa
        case Errored(e)    => IO.raiseError(e)
        case Canceled()    => IO.raiseError(new RuntimeException("Cancelled"))
    } yield result

  // 2
  def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] =
    for {
      fibA     <- ioa.debugs.start
      fibB     <- iob.debugs.start
      outcomeA <- fibA.join
      outcomeB <- fibB.join
      result <- (outcomeA, outcomeB) match {
        case (Succeeded(fa), Succeeded(fb)) => fa.both(fb)
        case (Errored(e), _)                => IO.raiseError(e)
        case (_, Errored(e))                => IO.raiseError(e)
        case _                              => IO.raiseError(new RuntimeException("Some fiber cancelled"))
      }
    } yield result

  // 3
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    for {
      fib     <- io.timeout(duration).start
      outcome <- fib.join
      result <- outcome match
        case Succeeded(fa) => fa
        case Errored(e)    => IO.raiseError(e)
        case Canceled()    => IO.raiseError(new RuntimeException("Timed out"))
    } yield result

  override def run: IO[Unit] = {
    // timeout(IO(4).debugs >> IO.sleep(1.seconds) >> IO(3), 2.second).debugs.void
    tupleIOs(IO(4), IO("Hello")).debugs.void
  }
}
