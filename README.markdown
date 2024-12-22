# Motivation for the Fork

While upgrading and modernizing Gitblit, it is helpful to retain the ability to use the "old" build mechanism for generating distribution packages as a reference for comparison. Once the Gitblit build process is fully supported by Gradle, this fork will be archived.

Please note that this fork contains some hardcoded configurations that may not function with other projects.

## Adopted technologies
- JDK: GraalVM JDK 21
- Gradle 8.x: for build

## Discarded Functionality
- Cobertura tasks have been removed and cannot be restored for use with modern JDKs
- Some Unit tests failing

## Hardcoded Dependencies
- Resolution of `org.ow2.asm` dependencies is hardcoded to version 9.7, if cannot be resolved.

# Original README
## Moxie Project Build Toolkit

Moxie is a small collection of ANT tasks to facilitate building Java projects.

**This is not production-ready!**

Please see the [documentation](http://gitblit-org.github.com/moxie) for details.
