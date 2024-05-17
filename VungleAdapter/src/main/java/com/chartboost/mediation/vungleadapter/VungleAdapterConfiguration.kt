package com.chartboost.mediation.vungleadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.vungle.ads.AdConfig
import com.vungle.ads.VungleAds

object VungleAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "vungle"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Vungle"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = VungleAds.getSdkVersion()

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_VUNGLE_ADAPTER_VERSION

    /**
     * Flag to optionally set to specify whether the back button will be immediately enabled
     * during the video ad, or it will be inactive until the on screen close button appears (the default behavior).
     * It can be set at any time and will take effect on the next ad request.
     *
     * Once enabled, the Android back button allows the user to skip the video ad and proceed
     * to the post-roll if one exists. If the ad does not have a post-roll, it simply ends.
     */
    var backBtnImmediatelyEnabled = false
        set(value) {
            field = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Vungle back button setting is ${if (value) "enabled" else "disabled"}.",
            )
        }

    /**
     * If set, Vungle will check if it has an ad that can be rendered in the specified orientation.
     * This flag can be set at any time and will take effect on the next ad request.
     *
     * See [AdConfig.Orientation] for available options.
     */
    var adOrientation: Int? = null
        set(value) {
            field = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Vungle ad orientation set to ${
                    when (value) {
                        AdConfig.PORTRAIT -> "PORTRAIT"
                        AdConfig.LANDSCAPE -> "LANDSCAPE"
                        AdConfig.AUTO_ROTATE -> "AUTO_ROTATE"
                        else -> "UNSPECIFIED"
                    }
                }.",
            )
        }
}
