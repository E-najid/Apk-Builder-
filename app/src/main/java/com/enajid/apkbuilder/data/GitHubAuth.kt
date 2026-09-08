package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.BuildConfig

/**
 * GitHub OAuth configuration.
 *
 * Register your own OAuth app at https://github.com/settings/developers
 * (choose "OAuth App" and tick "Enable Device Flow"), then set the client ID
 * via the `GITHUB_CLIENT_ID` Gradle property when building. See README.md.
 */
object GitHubAuth {
    val CLIENT_ID: String = BuildConfig.GITHUB_CLIENT_ID

    /** Least privilege: public repos only + pushing workflow files. */
    const val SCOPES = "public_repo workflow"

    const val DEFAULT_VERIFICATION_URL = "https://github.com/login/device"
}
