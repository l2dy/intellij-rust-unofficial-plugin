/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.workspace

import org.rust.cargo.CfgOptions
import org.rust.cargo.toolchain.impl.CargoMetadata
import org.rust.cargo.util.parseSemVer
import org.rust.stdext.HashCode
import java.net.URL
import java.nio.file.Path

typealias PackageId = String

/** Refers to [Package ID specification](https://doc.rust-lang.org/cargo/reference/pkgid-spec.html) */
data class PackageIdSpec(
    val name: String,
    val version: String?,
) {
    companion object {
        fun parse(spec: String): PackageIdSpec {
            if (spec.contains("://")) {
                val url = "http://" + spec.split("://", limit = 2)[1]
                return fromUrl(URL(url))
            }
            val parts = spec.split(':', '@', limit = 2)

            return if (parts.size > 1) {
                PackageIdSpec(parts[0], parts[1])
            } else {
                PackageIdSpec(parts[0], null)
            }
        }

        private fun fromUrl(url: URL): PackageIdSpec {
            val frag: String? = url.ref
            val pathName = url.path.substringAfterLast("/")

            if (frag == null || frag == "") {
                return PackageIdSpec(pathName, null)
            }

            val parts = frag.split(':', '@', limit = 2)

            return if (parts.size > 1) {
                // name ("@" | ":" ) semver
                PackageIdSpec(parts[0], parts[1])
            } else {
                // ( name | semver )
                val first = frag[0]
                if (first.isLetter() || first == '_') {
                    // A valid package name starts with a letter or `_`.
                    // https://github.com/rust-lang/cargo/blob/master/crates/cargo-util-schemas/src/restricted_names.rs#L64
                    PackageIdSpec(frag, null)
                } else {
                    frag.parseSemVer()
                    PackageIdSpec(pathName, frag)
                }
            }
        }
    }
}

/** Refers to [org.rust.cargo.project.workspace.PackageImpl.rootDirectory] */
typealias PackageRoot = Path

/** Refers to [cargo feature](https://doc.rust-lang.org/cargo/reference/features.html) name */
typealias FeatureName = String

/**
 * Cargo.toml:
 * ```
 * [features]
 * foo = [ "bar", "baz/quux" ]
 * #        ^dep   ^dep in other package
 * ```
 */
typealias FeatureDep = String

/**
 * A POD-style representation of [CargoWorkspace] used as an intermediate representation
 * between `cargo metadata` JSON and [CargoWorkspace] object graph.
 *
 * Dependency graph is represented via adjacency list, where `Index` is the order of a particular
 * package in `packages` list.
 */
data class CargoWorkspaceData(
    val packages: List<Package>,
    /** Resolved dependencies with package IDs in values (instead of just names and versions) */
    val dependencies: Map<PackageId, Set<Dependency>>,
    /** Dependencies as they listed in the package `Cargo.toml`, without package resolution or any additional data */
    val rawDependencies: Map<PackageId, List<CargoMetadata.RawDependency>>,
    val workspaceRootUrl: String? = null
) {
    data class Package(
        val id: PackageId,
        val contentRootUrl: String,
        val name: String,
        val version: String,
        val targets: Collection<Target>,
        val source: String?,
        val origin: PackageOrigin,
        val edition: CargoWorkspace.Edition,
        /** All features available in this package (including optional dependencies) */
        val features: Map<FeatureName, List<FeatureDep>>,
        /** Enabled features (from Cargo point of view) */
        val enabledFeatures: Set<FeatureName>,
        val cfgOptions: CfgOptions?,
        val env: Map<String, String>,
        val outDirUrl: String?,
        val procMacroArtifact: ProcMacroArtifact? = null,
    )

    data class Target(
        val crateRootUrl: String,
        val name: String,
        val kind: CargoWorkspace.TargetKind,
        val edition: CargoWorkspace.Edition,
        val doctest: Boolean,
        val requiredFeatures: List<FeatureName>
    )

    data class Dependency(
        val id: PackageId,
        val name: String? = null,
        val depKinds: List<CargoWorkspace.DepKindInfo> = listOf(CargoWorkspace.DepKindInfo(CargoWorkspace.DepKind.Unclassified))
    )

    data class ProcMacroArtifact(
        val path: Path,
        val hash: HashCode
    )
}
