name := "akka-cqrs-dsl"

organization := "io.digital-magic"
organizationHomepage := Some(url("http://www.digital-magic.io"))
startYear := Some(2016)
version := "1.0-SNAPSHOT"
scalaVersion := Dependencies.Versions.scala
scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:reflectiveCalls",
  "-language:postfixOps",
  "-language:implicitConversions"
)

libraryDependencies ++= Dependencies.dependencies

resolvers ++= Dependencies.repos
