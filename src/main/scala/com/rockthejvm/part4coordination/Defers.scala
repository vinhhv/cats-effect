package com.rockthejvm.part4coordination

import cats.effect.{Deferred, FiberIO, IO, IOApp, OutcomeIO, Ref}
import cats.syntax.traverse.*
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object Defers extends IOApp.Simple {

  // deferred is a primitive for waiting for an effect, while some other effect completes with a value
  val aDeferred: IO[Deferred[IO, Int]]    = Deferred[IO, Int]
  val aDeferred_v2: IO[Deferred[IO, Int]] = IO.deferred[Int] // same

  // get blocks the calling fiber (semantically) until some other fiber completes the Deferred with a value
  val reader: IO[Int] = aDeferred.flatMap { deferred =>
    deferred.get // blocks the fiber
  }

  val writer = aDeferred.flatMap { signal =>
    signal.complete(42)
  }

  def demoDeferred(): IO[Unit] = {
    def consumer(signal: Deferred[IO, Int]) = for {
      _             <- IO("[consumer] waiting for result...").debugs
      meaningOfLife <- signal.get // blocker
      _             <- IO(s"[consumer] got the result: $meaningOfLife").debugs
    } yield ()

    def producer(signal: Deferred[IO, Int]) = for {
      _             <- IO("[producer] crunching numbers...").debugs
      _             <- IO.sleep(1.second)
      _             <- IO("[producer] complete: 42").debugs
      meaningOfLife <- IO.pure(42)
      _             <- signal.complete(meaningOfLife)
    } yield ()

    for {
      signal      <- Deferred[IO, Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _           <- fibProducer.join
      _           <- fibConsumer.join
    } yield ()
  }

  // simulate downloading some content
  val fileParts = List("I ", "love S", "cala", " with Cat", "s Effect!<EOF>")

  def fileNotifierWithRef(): IO[Unit] = {
    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts
        .map { part =>
          IO(s"[downloader] got '$part'").debugs >> IO.sleep(1.second) >> contentRef.update(currentContext =>
            currentContext + part
          )
        }
        .sequence
        .void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] = for {
      file <- contentRef.get
      _ <-
        if (file.endsWith("<EOF>")) IO("[notifier] File download complete").debugs >> IO(file).debugs
        else
          IO("[notifier] downloading...").debugs >> IO.sleep(500.millis) >> notifyFileComplete(contentRef) // busy wait!
    } yield ()

    for {
      contentRef    <- Ref[IO].of("")
      fibDownloader <- downloadFile(contentRef).start
      notifier      <- notifyFileComplete(contentRef).start
      _             <- fibDownloader.join
      _             <- notifier.join
    } yield ()
  }

  // deferred works miracles for waiting
  def fileNotifierWithDeferred(): IO[Unit] = {
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] =
      for {
        _       <- IO("[notifier] downloading...").debugs
        content <- signal.get // blocks until the signal is completed
        _       <- IO(s"[notifier] File download complete: $content").debugs
      } yield ()

    def downloadFilePart(part: String, contentRef: Ref[IO, String], signal: Deferred[IO, String]): IO[Unit] =
      for {
        _       <- IO(s"[downloader] got '$part'").debugs
        _       <- IO.sleep(1.second)
        content <- contentRef.updateAndGet(_ + part)
        _       <- if (content.contains("<EOF>")) signal.complete(content) else IO.unit
      } yield ()

    for {
      contentRef   <- Ref[IO].of("")
      signal       <- Deferred[IO, String]
      notifierFib  <- notifyFileComplete(signal).start
      fileTasksFib <- fileParts.map(downloadFilePart(_, contentRef, signal)).sequence.start
      _            <- notifierFib.join
      _            <- fileTasksFib.join
    } yield ()
  }

  /**
   * Exercises:
   *  - (medium) write a small alarm notification with two simultaneous IOs
   *    - one that increments a counter every second (a clock)
   *    - one that waits for the counter to become 10, then prints a message "time's up!"
   *
   *  - (mega hard) implement racePair with Deferred.
   *    - use a Deferred which can hold an Either[outcome for ioa, outcome for iob]
   *    - start two fibers, one for each IO
   *    - on completion (with any status), each IO needs to complete that Deferred
   *      (hint: use a finalizer from the Resources lesson)
   *      (hint2: use a guarantee call to make sure the fibers complete the Deferred)
   *    - what do you do in case of cancellation (the hardest part)?
   */
  // 1
  def demoAlarmClock(): IO[Unit] = {
    def clock(count: Ref[IO, Int], signal: Deferred[IO, Unit], endCount: Int): IO[Unit] =
      for {
        currentCount <- count.getAndUpdate(_ + 1)
        _ <-
          if (currentCount >= endCount)
            IO(s"[clock] clock is $currentCount, signaling...").debugs >> signal.complete(())
          else IO.sleep(1.second) >> IO(s"[clock] count: $currentCount").debugs >> clock(count, signal, endCount)
      } yield ()

    def alarm(signal: Deferred[IO, Unit], endCount: Int): IO[Unit] =
      for {
        _ <- IO(s"[alarm] alarm set for $endCount").debugs
        _ <- signal.get
        _ <- IO(s"[alarm] ALARMING!").debugs
      } yield ()

    val alarmSetFor = 10
    for {
      clockRef <- Ref[IO].of(0)
      signal   <- Deferred[IO, Unit]
      clockFib <- clock(clockRef, signal, alarmSetFor).start
      alarmFib <- alarm(signal, alarmSetFor).start
      _        <- clockFib.join
      _        <- alarmFib.join
    } yield ()
  }

  // 2
  type RaceResult[A, B] = Either[
    (OutcomeIO[A], FiberIO[B]), // (winner result, loser fiber)
    (FiberIO[A], OutcomeIO[B])  // (loser fiber, winner result)
  ]

  type EitherOutcome[A, B] = Either[OutcomeIO[A], OutcomeIO[B]]

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] = IO.uncancelable { poll =>
    for {
      signal <- Deferred[IO, EitherOutcome[A, B]]
      fiba   <- ioa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
      fibb   <- iob.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
      result <- poll(signal.get).onCancel { // blocking call - should be cancellable
        for {
          cancelFibA <- fiba.cancel.start
          cancelFibB <- fibb.cancel.start
          _          <- cancelFibA.join
          _          <- cancelFibB.join
        } yield ()
      }
    } yield result match {
      case Left(outcomeA)  => Left((outcomeA, fibb))
      case Right(outcomeB) => Right((fiba, outcomeB))
    }
  }

  override def run: IO[Unit] = demoAlarmClock()
}
