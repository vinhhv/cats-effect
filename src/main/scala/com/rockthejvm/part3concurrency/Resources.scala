package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.kernel.Resource
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
    fib  <- (conn.open() *> IO.never).onCancel(conn.close().void).start
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

  /**
   * Resources
   */
  def connFromConfig(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket { scanner =>
        // acquire a connection based on the file
        IO(new Connection(scanner.nextLine())).bracket { conn =>
          conn.open() >> IO.never
        }(conn => conn.close().void)
      }(scanner => IO("closing file").debugs >> IO(scanner.close()))
  // nesting resources are tedious

  val connectionResource = Resource.make(IO(new Connection("rockthejvm.com")))(conn => conn.close().void)
  // .. at a later part of the code
  val resourceFetchUrl = for {
    fib <- connectionResource.use(conn => conn.open() >> IO.never).start
    _   <- IO.sleep(1.second) >> fib.cancel
  } yield ()

  // resources are equivalent to brackets
  val simpleResource                      = IO("some resource")
  val usingResource: String => IO[String] = string => IO(s"using the string: $string").debugs
  val releaseResource: String => IO[Unit] = string => IO(s"finalize the string: $string").debugs.void

  val usingResourceWithBracket  = simpleResource.bracket(usingResource)(releaseResource)
  val usingResourceWithResource = Resource.make(simpleResource)(releaseResource).use(usingResource)

  /**
   * Exercise: read a text file with one line every 100 millis, using Resource
   * (refactor the bracket exercise to use Resource)
   */
  def readTextFile(path: String): IO[Unit] = {
    val scanner =
      Resource.make(openFileScanner(path))(scanner => IO(s"closing file at $path").debugs >> IO(scanner.close()))
    scanner.use(readLineByLine)
  }

  def cancelReadFile(path: String) = for {
    fib <- readTextFile(path).start
    _   <- IO.sleep(2.seconds) >> fib.cancel
  } yield ()

  // nested resources
  def connFromConfResource(path: String): Resource[IO, Connection] =
    Resource
      .make(IO("opening file").debugs >> openFileScanner(path))(scanner =>
        IO("closing file").debugs >> IO(scanner.close())
      )
      .flatMap(scanner => Resource.make(IO(new Connection(scanner.nextLine())))(conn => conn.close().void))

  def connFromConfResourceClean(path: String): Resource[IO, Connection] = for {
    scanner <- Resource
      .make(IO("opening file").debugs >> openFileScanner(path))(scanner =>
        IO("closing file").debugs >> IO(scanner.close())
      )
    conn <- Resource.make(IO(new Connection(scanner.nextLine())))(conn => conn.close().void)
  } yield conn

  val openConnection = connFromConfResourceClean("src/main/resources/connection.txt").use(_.open() *> IO.never)
  val canceledConnection = for {
    fib <- openConnection.start
    _   <- IO.sleep(1.second) >> IO("cancelling!").debugs >> fib.cancel
  } yield ()
  // connection + file will close automatically

  // finalizers to regular IOs
  val ioWithFinalizer = IO("some resource").debugs.guarantee(IO("freeing resource").debugs.void)
  val ioWithFinalizer_v2 =
    (IO("some resource") >> IO.raiseError(new RuntimeException("something happened"))).debugs.guaranteeCase {
      case Succeeded(fa) => fa.flatMap(result => IO(s"releasing resource: $result").debugs).void
      case Errored(e)    => IO("nothing to release").debugs.void
      case Canceled()    => IO("resource got canceled, releasing what's left").debugs.void
    }

  override def run: IO[Unit] = ioWithFinalizer_v2.void
}
