val scala3Version = "3.4.2"
val scalusVersion = "0.7.2+3-189fc314-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

resolvers +=
    "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"

name := "scalus"

version := "0.1.0-SNAPSHOT"

scalaVersion := scala3Version

addCompilerPlugin("org.scalus" %% "scalus-plugin" % scalusVersion)

scalacOptions += "-Wunused:all"
scalacOptions += "-deprecation"
scalacOptions += "-feature"

libraryDependencies += "org.scalus" %% "scalus" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-bloxbean-cardano-client-lib" % scalusVersion
libraryDependencies += "com.bloxbean.cardano" % "cardano-client-lib" % "0.5.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-16" % "3.2.14.0" % "test"
