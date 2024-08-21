# IntelliJ Rust Community Plugin
This project is a community fork of now deprecated OpenSource [Rust plugin for Jetbrains IDEs](https://plugins.jetbrains.com/plugin/8182--come-on-dont-kill-opensource-rust) (https://github.com/intellij-rust/intellij-rust)
This repo is not relying on Jetbrains' "c-capable" or "rust-capable" plugins, which means that it potentially might be installed onto each and any Jetbrains IDE

The original repo has become unsupported after the announcement of RustRover, but there is a lot of opensource in this repo that we can leverage to keep the community opensource plugin alive.

For any additional information refer to [their announcement](https://blog.jetbrains.com/rust/2023/09/13/introducing-rustrover-a-standalone-rust-ide-by-jetbrains/#existing-open-source-plugin) at the original repo.


##### Official Rust plugin for the IntelliJ Platform
[There is an official proprietary Rust plugin from JetBrains IDEs](https://plugins.jetbrains.com/plugin/22407-rust).
You may find it in the official marketplace and in RustRover.

## Installation & Usage
Grab a zip archive from `Releases` and then add the archive as a "plugin from a filesystem" (File -> Settings -> Gear Icon on the top right -> Install plugin from disk)
To open an existing project, use **File | Open** and point to the directory containing `Cargo.toml`.
For creating projects, use the **Rust** template.


[//]: #  "All the plugin's features are described in [documentation](https://plugins.jetbrains.com/plugin/8182-rust/docs)."
[//]: #  "New features are regularly announced in [changelogs](https://intellij-rust.github.io/thisweek/)."


## Compatible IDEs

So far this plugin can probably be installed onto the same IDE list that was available the [original plugin](https://github.com/intellij-rust/intellij-rust/?tab=readme-ov-file#compatible-ides).

As for now, plugin supports latest stable and the most previous stable version (although it can be changed in future).
Consider using the original Rust plugin if you need to work with a version earlier than 2024.1; Consider contributing if this community plugin  does not enable support of the new IDEs fast enough.

This plugin has been proven to work with:
* PyCharm 2024.2 Community as well as Professional

### Advanced features
There were several features present in the original plugin besides the original language support:

* debugging
* profiler
* valgrind
* clion, appcode, etc extensions

These features had been cut for now to make maintaining plugin up-to-date easier.


## Contributing

Refer to [ARCHITECTURE.md] for a structural advice, and to [CONTRIBUTING.md] for general good practices.

If something is horribly wrong, feel free to open an issue.

[CONTRIBUTING.md]: CONTRIBUTING.md
[ARCHITECTURE.md]: ARCHITECTURE.md
