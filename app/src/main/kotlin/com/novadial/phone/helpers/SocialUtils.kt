package com.novadial.phone.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.fossify.commons.extensions.toast
import com.novadial.phone.R

object SocialUtils {

    fun openWhatsApp(context: Context, number: String) {
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "https://api.whatsapp.com/send?phone=$cleanNumber"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            context.toast(R.string.no_app_found)
        }
    }

    fun openTelegram(context: Context, number: String) {
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "tg://msg?text=&to=$cleanNumber"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$cleanNumber"))
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                context.toast(R.string.no_app_found)
            }
        }
    }

    fun openSignal(context: Context, number: String) {
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "sgnl://signal.me/#p/$cleanNumber"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://signal.me/#p/$cleanNumber"))
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                context.toast(R.string.no_app_found)
            }
        }
    }

    fun openViber(context: Context, number: String) {
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "viber://chat?number=$cleanNumber"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            context.toast(R.string.no_app_found)
        }
    }
}
