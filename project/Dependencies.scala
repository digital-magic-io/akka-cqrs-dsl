import sbt._

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
    val akkaPersistenceInMemory = "2.5.1.1"
  }

  def scalaReflect(v: String): Seq[ModuleID] = Seq("org.scala-lang" % "scala-reflect" % v)

  val dependencies = Seq(
    "org.scalaz"                 %% "scalaz-core"               % Versions.scalaz,
    "io.frees"                   %% "iotaz-core"                % Versions.iotaz,
    "com.github.mpilquist"       %% "simulacrum"                % Versions.simulacrum,
    "io.spray"                   %% "spray-json"                % Versions.sparyJson,
    "com.esotericsoftware"       %  "kryo"                      % Versions.kryoVersion,
    "com.typesafe.akka"          %% "akka-actor"                % Versions.akka,
    "com.typesafe.akka"          %% "akka-persistence"          % Versions.akka,
    "com.typesafe.akka"          %% "akka-cluster-sharding"     % Versions.akka,
    "com.typesafe.akka"          %% "akka-testkit"              % Versions.akka      % Test,
    "org.scalatest"              %% "scalatest"                 % Versions.scalaTest % Test,
    "com.github.dnvriend"        %% "akka-persistence-inmemory" % Versions.akkaPersistenceInMemory % Test
  )
}
