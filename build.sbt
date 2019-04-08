import sbt.Keys._

organizationHomepage := Some(url("http://www.digital-magic.io"))
startYear            := Some(2016)

val buildSettings = Seq(
  organization := "io.digital-magic",
  version      := "2.0.8",  // don't forget to bump version in .bintray.json
  parallelExecution in Test := false,
  scalaVersion       := Dependencies.Versions.scala212,
  crossScalaVersions := Seq(Dependencies.Versions.scala212),
  scalacOptions := Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-language:reflectiveCalls",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-Ypartial-unification"
  ),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    "dnvriend" at "http://dl.bintray.com/dnvriend/maven"
  )
)

lazy val core = Project(id = "akka-cqrs-dsl-core", base = file("akka-cqrs-dsl-core"))
  .settings(buildSettings)
  .settings(Dependencies.coreDependencies)

lazy val kryo = Project(id = "akka-cqrs-dsl-kryo", base = file("akka-cqrs-dsl-kryo"))
  .settings(buildSettings)
  .settings(Dependencies.kryoDependencies)
  .dependsOn(core)

lazy val spray = Project(id = "akka-cqrs-dsl-spray", base = file("akka-cqrs-dsl-spray"))
  .settings(buildSettings)
  .settings(Dependencies.sprayDependencies)
  .dependsOn(core)

lazy val root = Project(id = "akka-cqrs-dsl", base = file("."))
  .settings(buildSettings)
  .aggregate(core)
  .aggregate(kryo)
  .aggregate(spray)
