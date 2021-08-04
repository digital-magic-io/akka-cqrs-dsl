import sbt.Keys._

organizationHomepage := Some(url("http://www.digital-magic.io"))
startYear            := Some(2016)
Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.ScalaLibrary

val scala212Options = Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:reflectiveCalls",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-Ypartial-unification",
  "-language:experimental.macros"
)

val scala213Options = scala212Options.filterNot(_ == "-Ypartial-unification")

val buildSettings = Seq(
  organization := "io.digital-magic",
  version      := "2.1.1",  // don't forget to bump version in .bintray.json
  Test / parallelExecution := false,
  scalaVersion       := Dependencies.Versions.scala_2_12,
  crossScalaVersions := Seq(Dependencies.Versions.scala_2_12, Dependencies.Versions.scala_2_13),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => scala212Options
    case Some((2, 13)) => scala213Options
    case _ => Nil
  }),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases")
  )
)

lazy val coproduct = Project(id = "akka-cqrs-dsl-coproduct", base = file("akka-cqrs-dsl-coproduct"))
  .settings(buildSettings)
  .settings(Dependencies.coproductDependencies)

lazy val core = Project(id = "akka-cqrs-dsl-core", base = file("akka-cqrs-dsl-core"))
  .settings(buildSettings)
  .settings(Dependencies.coreDependencies)
  .dependsOn(coproduct)

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
  .aggregate(coproduct)
  .aggregate(core)
  .aggregate(kryo)
  .aggregate(spray)
