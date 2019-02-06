import sbt._
import sbt.Keys._

object Dependencies {

  object Versions {
    val scala212                = "2.12.8"
    val scalaz                  = "7.2.27"
    val iotaz                   = "0.3.10"
    val simulacrum              = "0.14.0"
    val sparyJson               = "1.3.5"
    val kryoVersion             = "4.0.2"
    val akka                    = "2.5.19"
    val scalaTest               = "3.0.5"
    val akkaPersistenceInMemory = "2.5.15.1"
    val specs2Version           = "4.3.6"
    val scalacheckVersion       = "1.14.0"
    val akkaKryoSerialization   = "0.5.2"
  }

  def scalaReflect(v: String): Seq[ModuleID] = Seq("org.scala-lang" % "scala-reflect" % v)

  val commonDependencies = Seq(
    "org.scalatest"              %% "scalatest"                 % Versions.scalaTest % Test,
    compilerPlugin("org.spire-math" % "kind-projector" % "0.9.9" cross CrossVersion.binary)
  )

  val coreDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "org.scalaz"                 %% "scalaz-core"               % Versions.scalaz,
    "io.frees"                   %% "iotaz-core"                % Versions.iotaz,
    "com.typesafe.akka"          %% "akka-actor"                % Versions.akka,
    "com.typesafe.akka"          %% "akka-persistence"          % Versions.akka,
    "com.typesafe.akka"          %% "akka-cluster-sharding"     % Versions.akka,
    "com.typesafe.akka"          %% "akka-testkit"              % Versions.akka      % Test,
    "com.github.dnvriend"        %% "akka-persistence-inmemory" % Versions.akkaPersistenceInMemory % Test,
    "org.specs2"                 %% "specs2-core"               % Versions.specs2Version % Test,
    "org.specs2"                 %% "specs2-scalacheck"         % Versions.specs2Version % Test,
    "org.scalacheck"             %% "scalacheck"                % Versions.scalacheckVersion % Test,
    "org.scalaz"                 %% "scalaz-scalacheck-binding" % (Versions.scalaz + "-scalacheck-1.14") % Test,
  ) ++ scalaVersion(sv => Dependencies.scalaReflect(sv)).value

  val kryoDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "com.esotericsoftware"       %  "kryo"                       % Versions.kryoVersion,
    "com.github.romix.akka"      %% "akka-kryo-serialization"    % Versions.akkaKryoSerialization % Test
  )

  val sprayDependencies = libraryDependencies ++= commonDependencies ++ Seq(
    "io.spray"                   %% "spray-json"                % Versions.sparyJson,
  )
}
