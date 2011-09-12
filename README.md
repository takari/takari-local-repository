Tesla Concurrent Local Repository
=================================

This extension for Tesla enables safe concurrent use of the local repository such that multiple builds can concurrently
resolve and install artifacts to a shared local repository. This is especially useful for continuous integration systems
that usually build multiple projects in parallel and want to share the same local repository to reduce disk consumption.

Note that this extension is only concerned with the data integrity of the local repository at the artifact/metadata file
level. It does not provide all-or-nothing installation of artifacts produced by a given build.
