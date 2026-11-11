package com.novadial.phone.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fossify.commons.R
import org.fossify.commons.activities.BaseComposeActivity
import org.fossify.commons.activities.FAQActivity
import org.fossify.commons.activities.LicenseActivity
import org.fossify.commons.compose.extensions.enableEdgeToEdgeSimple
import org.fossify.commons.compose.lists.SimpleColumnScaffold
import org.fossify.commons.compose.settings.SettingsGroup
import org.fossify.commons.compose.settings.SettingsListItem
import org.fossify.commons.compose.settings.SettingsTitleTextComponent
import org.fossify.commons.compose.theme.AppThemeSurface
import org.fossify.commons.compose.theme.SimpleTheme
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.launchViewIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import com.novadial.phone.BuildConfig

class AboutActivity : BaseComposeActivity() {
    private var firstVersionClickTS = 0L
    private var clicksSinceFirstClick = 0

    companion object {
        private const val EASTER_EGG_TIME_LIMIT = 3000L
        private const val EASTER_EGG_REQUIRED_CLICKS = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeSimple()

        setContent {
            AppThemeSurface {
                val context = LocalContext.current
                val titleStartPadding = Modifier.padding(start = 40.dp)

                SimpleColumnScaffold(
                    title = context.getString(R.string.about),
                    goBack = ::finish
                ) {
                    // 1. Support Section
                    val faqItems = remember {
                        intent.getSerializableExtra(APP_FAQ) as? ArrayList<FAQItem>
                    }
                    val setupFAQ = !faqItems.isNullOrEmpty()

                    SettingsGroup(title = {
                        SettingsTitleTextComponent(
                            text = context.getString(R.string.support),
                            modifier = titleStartPadding
                        )
                    }) {
                        if (setupFAQ) {
                            SettingsListItem(
                                tint = SimpleTheme.colorScheme.onSurface,
                                click = ::launchFAQActivity,
                                text = context.getString(R.string.frequently_asked_questions),
                                icon = R.drawable.ic_help_outline_vector
                            )
                        }

                        // Known Issues (pointing to NovaDial's issue tracker)
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/dhilipmpms/NovaDial/issues?q=is:open+is:issue+label:bug")
                            },
                            text = context.getString(R.string.known_issues),
                            icon = R.drawable.ic_bug_report_outline_vector
                        )

                        // Branding text block replacing hello@fossify.org
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            text = "NovaDial",
                            description = "A fork of Fossify Phone with highly customized tuning and enhancements for general-purpose calling.",
                            icon = R.drawable.ic_info_outline_vector
                        )
                    }

                    // 2. Credits Section
                    SettingsGroup(title = {
                        SettingsTitleTextComponent(
                            text = "Credits",
                            modifier = titleStartPadding
                        )
                    }) {
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/FossifyOrg/Phone")
                            },
                            text = "Original Project",
                            description = "https://github.com/FossifyOrg/Phone",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/dhilipmpms")
                            },
                            text = "NovaDial Maintainer",
                            description = "https://github.com/dhilipmpms",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/dhilipmpms/NovaDial")
                            },
                            text = "NovaDial Source",
                            description = "https://github.com/dhilipmpms/NovaDial",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            text = "NovaDial is a fork of Fossify Phone.",
                            description = "Full credit to Fossify for the original project and open-source foundation."
                        )
                    }

                    // Special thanks (call recording)
                    SettingsGroup(title = {
                        SettingsTitleTextComponent(
                            text = "Special thanks",
                            modifier = titleStartPadding
                        )
                    }) {
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/kitsumed/ShizuCallRecorder")
                            },
                            text = "ShizuCallRecorder by kitsumed",
                            description = "NovaDial's call recording is based on ShizuCallRecorder, available at https://github.com/kitsumed/ShizuCallRecorder (GPL-3.0)",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/RikkaApps/Shizuku")
                            },
                            text = "Shizuku",
                            description = "https://github.com/RikkaApps/Shizuku",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/martintobola-cz")
                            },
                            text = "Martin Tobola",
                            description = "For sharing his Shizuku call recording integration",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/Genymobile/scrcpy")
                            },
                            text = "scrcpy by Genymobile",
                            description = "scrcpy-server is used to capture call audio (Apache-2.0)",
                            icon = R.drawable.ic_github_vector
                        )
                    }

                    // 3. GitHub Section
                    SettingsGroup(title = {
                        SettingsTitleTextComponent(
                            text = "GitHub",
                            modifier = titleStartPadding
                        )
                    }) {
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/dhilipmpms/NovaDial")
                            },
                            text = "⭐ Star NovaDial on GitHub",
                            description = "https://github.com/dhilipmpms/NovaDial",
                            icon = R.drawable.ic_github_vector
                        )

                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = {
                                launchViewIntent("https://github.com/dhilipmpms")
                            },
                            text = "👨💻 Developer GitHub",
                            description = "https://github.com/dhilipmpms",
                            icon = R.drawable.ic_github_vector
                        )
                    }

                    // 4. Other Section
                    SettingsGroup(title = {
                        SettingsTitleTextComponent(
                            text = context.getString(R.string.other),
                            modifier = titleStartPadding
                        )
                    }) {
                        // Privacy Policy
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = ::onPrivacyPolicyClick,
                            text = context.getString(R.string.privacy_policy),
                            icon = R.drawable.ic_policy_outline_vector
                        )

                        // Third-party Licenses
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = ::onLicenseClick,
                            text = context.getString(R.string.third_party_licences),
                            icon = R.drawable.ic_article_outline_vector
                        )

                        // Version & Package info
                        SettingsListItem(
                            tint = SimpleTheme.colorScheme.onSurface,
                            click = ::onVersionClick,
                            text = "NovaDial Version ${BuildConfig.VERSION_NAME}",
                            description = BuildConfig.APPLICATION_ID,
                            icon = R.drawable.ic_info_outline_vector,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    private fun launchFAQActivity() {
        val faqItems = intent.getSerializableExtra(APP_FAQ) as? ArrayList<FAQItem> ?: return
        Intent(applicationContext, FAQActivity::class.java).apply {
            putExtra(APP_ICON_IDS, intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList<Int>())
            putExtra(APP_LAUNCHER_NAME, intent.getStringExtra(APP_LAUNCHER_NAME) ?: "")
            putExtra(APP_FAQ, faqItems)
            startActivity(this)
        }
    }

    private fun onPrivacyPolicyClick() {
        val url = "https://www.fossify.org/policy/phone"
        launchViewIntent(url)
    }

    private fun onLicenseClick() {
        Intent(applicationContext, LicenseActivity::class.java).apply {
            putExtra(APP_ICON_IDS, intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList<Int>())
            putExtra(APP_LAUNCHER_NAME, intent.getStringExtra(APP_LAUNCHER_NAME) ?: "")
            putExtra(APP_LICENSES, intent.getLongExtra(APP_LICENSES, 0))
            startActivity(this)
        }
    }

    private fun onVersionClick() {
        if (firstVersionClickTS == 0L) {
            firstVersionClickTS = System.currentTimeMillis()
            Handler(Looper.getMainLooper()).postDelayed({
                firstVersionClickTS = 0L
                clicksSinceFirstClick = 0
            }, EASTER_EGG_TIME_LIMIT)
        }

        clicksSinceFirstClick++
        if (clicksSinceFirstClick >= EASTER_EGG_REQUIRED_CLICKS) {
            toast(R.string.hello)
            firstVersionClickTS = 0L
            clicksSinceFirstClick = 0
        }
    }
}
