import sbt._
import sbt.Keys._

object Dependencies {

  object Versions {
    val scala_2_12              = "2.12.14"
    val scala_2_13              = "2.13.6"
    val scalaz                  = "7.3.3"
    val sprayJson               = "1.3.6"
    val kryoVersion             = "4.0.2"
    val akka                    = "2.5.30"
    val scalaTest               = "3.0.8"
    val specs2Version           = "4.7.0"
    val scalacheckVersion       = "1.14.0"
    val akkaKryoSerialization   = "1.1.5"
  }

  def scalaReflect(v: String): Seq[ModuleID] = Seq("org.scala-lang" % "scala-reflect" % v)

  val commonDependencies = Seq(
    "org.scalatest"              %% "scalatest"                 % Versions.scalaTest % Test,
    compilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full)
  )

  val coreDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "org.scalaz"                 %% "scalaz-core"               % Versions.scalaz,
    "com.typesafe.akka"          %% "akka-actor"                % Versions.akka,
    "com.typesafe.akka"          %% "akka-persistence"          % Versions.akka,
    "com.typesafe.akka"          %% "akka-cluster-sharding"     % Versions.akka,
    "com.typesafe.akka"          %% "akka-testkit"              % Versions.akka      % Test,
    "org.specs2"                 %% "specs2-core"               % Versions.specs2Version % Test,
    "org.specs2"                 %% "specs2-scalacheck"         % Versions.specs2Version % Test,
    "org.scalacheck"             %% "scalacheck"                % Versions.scalacheckVersion % Test,
    "org.scalaz"                 %% "scalaz-scalacheck-binding" % Versions.scalaz % Test,
  ) ++ scalaVersion(sv => Dependencies.scalaReflect(sv)).value

  val kryoDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "com.esotericsoftware"       %  "kryo"                       % Versions.kryoVersion,
    "io.altoo"                   %% "akka-kryo-serialization"    % Versions.akkaKryoSerialization % Test,
  )

  val sprayDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "io.spray"                   %% "spray-json"                % Versions.sprayJson,
  )
}
