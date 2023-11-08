package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object CancellingIOs extends IOApp.Simple {

  /*
    Cancelling IOs
    - fib.cancel
    - IO.race & other APIs
    - manual cancellation
   */
  val chainOfIOs: IO[Int] = IO("waiting").debugs >> IO.canceled >> IO(42).debugs

  // uncancelable
  // example: online store, payment processor
  // payment process must NOT be cancelled
  val specialPaymentSystem =
    (
      IO("Payment running, don't cancel me...").debugs >>
        IO.sleep(1.second) >>
        IO("Payment completed.").debugs
    ).onCancel(IO("MEGA CANCEL OF DOOM!").debugs.void)

  val cancellationOfDoom = for {
    fib <- specialPaymentSystem.start
    _   <- IO.sleep(500.millis) >> fib.cancel
    _   <- fib.join
  } yield ()

  val atomicPayment    = IO.uncancelable(_ => specialPaymentSystem) // "masking"
  val atomicPayment_v2 = specialPaymentSystem.uncancelable

  val noCancellationOfDoom = for {
    fib <- atomicPayment.start
    _   <- IO.sleep(500.millis) >> IO("attempting cancellation...").debugs >> fib.cancel
    _   <- fib.join
  } yield ()

  /*
    The uncancelable API is more complex and more general.
    It takes a function from Poll[IO] to IO. In example above, we aren't using the Poll instance.
    The Poll object can be used to mark sections within the returned effort which CAN BE CANCELLED.
   */
  /*
    Example: auth service. Has two parts:
    - input password, can be cancelled, because otherwise we might block indefinitely on user input
    - verify password CANNOT be cancelled once it's started
   */
  val inputPassword: IO[String] =
    IO("Input password:").debugs >> IO("(typing password)").debugs >> IO.sleep(2.seconds) >> IO("RockTheJVM!")
  val verifyPassword: String => IO[Boolean] = (pw: String) =>
    IO("verifying...").debugs >> IO.sleep(2.seconds) >> IO(pw == "RockTheJVM!")

  val authFlow: IO[Unit] = IO.uncancelable { poll =>
    for {
      pw <- poll(inputPassword).onCancel(IO("Authentication timed out. Try again later.").debugs.void) // cancellable
      verified <- verifyPassword(pw) // not cancellable
      _ <-
        if (verified) IO("Authentication successful.").debugs
        else IO("Authentication failed.").debugs
    } yield ()
  }

  val authProgram = for {
    authFib <- authFlow.start
    _       <- IO.sleep(3.seconds) >> IO("Authentication timeout, attempting cancel...").debugs >> authFib.cancel
    _       <- authFib.join
  } yield ()

  /*
    Uncancellable calls are MASKS which suppress cancellation.
    Poll calls are "gaps opened" in the uncancellable region.
   */

  /**
   * Exercises
   */
  // 1
  val cancelBeforeMol  = IO.canceled >> IO(42).debugs
  val uncancellableMol = IO.uncancelable(_ => IO.canceled >> IO(42).debugs)
  // uncancelable will eliminate ALL cancel points

  // 2
  val invincibleAuthProgram = for {
    authFib <- IO.uncancelable(_ => authFlow).start
    _       <- IO.sleep(1.seconds) >> IO("Authentication timeout, attempting cancel...").debugs >> authFib.cancel
    _       <- authFib.join
  } yield ()

  // 3
  def threeStepProgram(): IO[Unit] = {
    val sequence = IO.uncancelable { poll =>
      poll(IO("cancellable").debugs >> IO.sleep(1.second) >> IO("cancellable end").debugs) >>
        IO("uncancellable").debugs >> IO.sleep(1.second) >> IO("uncancellable end").debugs >>
        poll(IO("second cancelable").debugs >> IO.sleep(1.second) >> IO("second cancellable end").debugs)
    }

    for {
      fib <- sequence.start
      _   <- IO.sleep(1500.millis) >> IO("CANCELLING").debugs >> fib.cancel
      _   <- fib.join
    } yield ()
  }

  override def run: IO[Unit] = threeStepProgram()
}
