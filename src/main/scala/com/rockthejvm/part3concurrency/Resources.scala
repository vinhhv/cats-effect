package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import com.rockthejvm.utils.*

import java.io.{File, FileReader}
import java.util.Scanner
import scala.concurrent.duration.*

object Resources extends IOApp.Simple {

  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open(): IO[String]  = IO(s"opening connection to $url").debugs
    def close(): IO[String] = IO(s"closing connection to $url").debugs
  }

  val asyncFetchUrl = for {
    fib <- (new Connection("rockthejvm.com").open() *> IO.sleep(Int.MaxValue.seconds)).start
    _   <- IO.sleep(1.second) *> fib.cancel
  } yield ()
  // problem: leaking resources

  val correctAsyncFetchUrl = for {
    conn <- IO(new Connection("rockthejvm"))
    fib  <- (conn.open() *> IO.sleep(Int.MaxValue.seconds)).onCancel(conn.close().void).start
    _    <- IO.sleep(1.second) *> fib.cancel
  } yield ()

  /*
    bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
    bracket is equivalent to try-catches (pure FP)
   */
  val bracketFetchUrl = IO(new Connection("rockthejvm.com"))
    .bracket(_.open() *> IO.sleep(Int.MaxValue.seconds))(_.close().void)

  val bracketProgram = for {
    fib <- bracketFetchUrl.start
    _   <- IO.sleep(1.second) *> fib.cancel
  } yield ()

  /**
   * Exercise: read the file with the bracket pattern
   * - open a scanner
   * - read the file line by line, every 100 millis
   * - close the scanner
   * - if cancelled/throws error, close the scanner
   */
  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))

  def readLineByLine(scanner: Scanner): IO[Unit] =
    if (scanner.hasNextLine) IO(scanner.nextLine()).debugs >> IO.sleep(100.millis) >> readLineByLine(scanner)
    else IO.unit

  def bracketReadFile(path: String): IO[Unit] =
    IO(s"opening file at $path") >> openFileScanner(path).bracket { scanner =>
      readLineByLine(scanner)
    } { scanner =>
      IO(s"closing file at $path").debugs >> IO(scanner.close())
    }

  override def run: IO[Unit] = bracketReadFile("src/main/scala/com/rockthejvm/part3concurrency/Resources.scala")
}
