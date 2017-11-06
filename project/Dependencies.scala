import sbt._

object Dependencies {

  object Versions {
    val scala211  = "2.11.11"
    val scala212  = "2.12.4"
    val akka      = "2.4.20"
    val scalaTest = "3.0.4"
  }

  val dependencies = Seq(
    "com.typesafe.akka" %% "akka-actor"   % Versions.akka,
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka      % Test,
    "org.scalatest"     %% "scalatest"    % Versions.scalaTest % Test
  )
}
