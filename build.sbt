val scala3Version = "3.3.0"
val scalusVersion = "0.5.0"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

resolvers +=
    "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"

name := "scalus"

version := "0.1.0-SNAPSHOT"

scalaVersion := scala3Version

addCompilerPlugin("org.scalus" %% "scalus-plugin" % scalusVersion)

scalacOptions += "-Wunused:all"

libraryDependencies += "org.scalus" %% "scalus" % scalusVersion
libraryDependencies += "com.bloxbean.cardano" % "cardano-client-lib" % "0.4.3"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-16" % "3.2.14.0" % "test"
