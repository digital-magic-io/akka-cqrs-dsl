import sbt._

object Dependencies {

  object Versions {
    val scala211  = "2.11.11"
    val scala212  = "2.12.6"
    val akka      = "2.5.13"
    val scalaTest = "3.0.5"
  }

  val dependencies = Seq(
    "com.typesafe.akka" %% "akka-actor"   % Versions.akka,
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka      % Test,
    "org.scalatest"     %% "scalatest"    % Versions.scalaTest % Test
  )
}
