# Linker

Linker is an attempt at making it easier to work with leiningen sub
projects

# Introduction

When a clojure application is comprised of many Leiningen sub
projects, it becomes increasingly difficult to navigate around a code
base. Instead of having a single tree of code, now there are multiple
roots. In an environment like emacs, this means constantly navigating
back to a top level, before descending back down into
project-b/src/foo/bar/baz.clj.

Linker is a (very experimental) solution to ease that problem. Linker
takes a list of sub projects, and creates a super project that links
to all source files in the sub project.

## Usage

Linker is just a clojure project that can be required, or run from the
command line. It takes one argument, a map that includes all
information required to generate the super project. This is known as a
spec. For a fully documented spec, see the [sample spec file]().

Linker takes the spec file, and uses it perform 2 actions

**Generate super project.clj**

Linker produces a new Leiningen project.clj at the root of the super
project that is a merge of all sub project.cljs. For now, this means
taking all the `:dependencies` in the default and `:dev` profiles and
concatenating them all together into one list. If more than one
version of a dependency is present in different projects, the one with
the highest version will win.

**Symlink project source files**

Linker then descends into each sub project, and creates a symbolic
link from the new super project to the sub project. In this way a file
tree is built under a single /src directory.

To run from the command line, cd into the linker project and run

```
lein run spec.edn
```

The new project will be created in `(:out-dir spec)`.

## Workflow

### Navigating

Now that your new project is created, open the super project in the
IDE of your choice. All your source files are under a single /src and
the new project.clj has all the information required to start a repl.
Now if you jump to a another project's function definition in your
IDE, instead of taking you to the file inside the jar file, it will
take you to the symlink file. So any edits will be made back to the
original file.

### Creating new files

When you create a new file, you need to add it to the original
project's source directory, and re-run linker. In future, it would
make sense to add a watcher to the sub project root directories so
that a background linker could automatically add the new symlinks

### Committing changes

Nothing new here. Make changes in the super project, and commit your
changes in the original projects


## License

Copyright © 2015 Anthony Marcar

Distributed under the Eclipse Public License either version 1.0
