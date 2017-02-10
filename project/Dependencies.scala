import sbt._

object Dependencies {

  object Versions {
    val scala211  = "2.11.8"
    val scala212  = "2.12.1"
    val akka      = "2.4.16"
    val scalaTest = "3.0.1"
  }

  val dependencies = Seq(
    "com.typesafe.akka" %% "akka-actor"   % Versions.akka,
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka      % Test,
    "org.scalatest"     %% "scalatest"    % Versions.scalaTest % Test
  )
}
