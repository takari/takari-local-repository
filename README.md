# Takari Concurrent Local Repository

This extension for Takari enables safe concurrent use of the local repository such that multiple builds can concurrently
resolve and install artifacts to a shared local repository. This is especially useful for continuous integration systems
that usually build multiple projects in parallel and want to share the same local repository to reduce disk consumption.

Note that this extension is only concerned with the data integrity of the local repository at the artifact/metadata file
level. It does not provide all-or-nothing installation of artifacts produced by a given build.

## Installing

To use the Takari Local Repository you must install it in Maven's `lib/ext` folder:

```
curl -O http://repo1.maven.org/maven2/io/takari/aether/takari-local-repository/0.10.4/takari-local-repository-0.10.4.jar
cp takari-local-repository-0.10.4.jar $MAVEN_HOME/lib/ext

curl -O http://repo1.maven.org/maven2/io/takari/takari-filemanager/0.8.2/takari-filemanager-0.8.2.jar
cp takari-filemanager-0.8.2.jar $MAVEN_HOME/lib/ext
```

## Using

Nothing special needs to be done to use the Takari Local Repository implementation. With the above files in the extension folder you now have process/thread safe local repository access!