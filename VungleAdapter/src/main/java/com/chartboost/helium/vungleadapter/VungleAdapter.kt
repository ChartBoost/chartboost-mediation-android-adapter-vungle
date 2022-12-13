package com.chartboost.helium.vungleadapter

import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.vungle.warren.*
import com.vungle.warren.Vungle.Consent
import com.vungle.warren.error.VungleException
import com.vungle.warren.error.VungleException.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 *  The Helium Vungle Adapter.
 */
class VungleAdapter : PartnerAdapter {
    companion object {
        /**
         * Flag to optionally set to disable Vungle's banner refresh. It must be set before the Vungle
         * SDK is initialized for it to take effect.
         */
        public var disableBannerRefresh = false
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle banner ad refresh is ${if (value) "disabled" else "enabled"}."
                )
            }

        /**
         * Flag to optionally set to enable Vungle's mute setting. It can be set at any time and
         * will take effect on the next ad request.
         */
        public var mute = false
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle mute setting is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Flag to optionally set to enable/disable immersive mode for API 19 and newer. It can be
         * set at any time and will take effect on the next ad request. Defaults to true.
         *
         * https://developer.android.com/training/system-ui/immersive.html.
         */
        public var immersiveMode = true
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle immersive mode is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Flag to optionally set to specify whether the back button will be immediately enabled
         * during the video ad, or it will be inactive until the on screen close button appears (the default behavior).
         * It can be set at any time and will take effect on the next ad request.
         *
         * Once enabled, the Android back button allows the user to skip the video ad and proceed
         * to the post-roll if one exists. If the ad does not have a post-roll, it simply ends.
         */
        public var backBtnImmediatelyEnabled = false
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle back button setting is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Flag to optionally set to specify whether the video transition animation should be enabled.
         * It can be set at any time and will take effect on the next ad request.
         *
         * The default is enabled, which is a fade-in/fade-out animation.
         */
        public var transitionAnimationEnabled = true
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle transition animation setting is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * If set, Vungle will check if it has an ad that can be rendered in the specified orientation.
         * This flag can be set at any time and will take effect on the next ad request.
         *
         * See [AdConfig.Orientation] for available options.
         */
        public var adOrientation: Int? = null
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Vungle ad orientation set to ${
                        when (value) {
                            AdConfig.PORTRAIT -> "PORTRAIT"
                            AdConfig.LANDSCAPE -> "LANDSCAPE"
                            AdConfig.AUTO_ROTATE -> "AUTO_ROTATE"
                            AdConfig.MATCH_VIDEO -> "MATCH_VIDEO"
                            else -> "UNSPECIFIED"
                        }
                    }."
                )
            }

        /**
         * Key for parsing the Vungle app ID.
         */
        private const val APP_ID_KEY = "vungle_app_id"
    }

    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Track the Vungle ad markup for fullscreen ad load --> ad show cycle, keyed by the Vungle placement ID.
     */
    private var adms: MutableMap<String, String?> = mutableMapOf()

    /**
     * Set holding all currently showing Vungle banner placements.
     */
    private val showingBanners: MutableSet<String> = mutableSetOf()

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "vungle"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Vungle"

    /**
     * Get the Vungle SDK version.
     */
    override val partnerSdkVersion: String
        get() = com.vungle.warren.BuildConfig.VERSION_NAME

    /**
     * Get the Vungle adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_VUNGLE_ADAPTER_VERSION

    /**
     * Initialize the Vungle SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Vungle.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials.optString(APP_ID_KEY).trim().takeIf { it.isNotEmpty() }
                ?.let { appId ->
                    Vungle.init(appId, context.applicationContext, object : InitCallback {
                        override fun onSuccess() {
                            Plugin.addWrapperInfo(
                                VungleApiClient.WrapperFramework.vunglehbs,
                                adapterVersion
                            )

                            continuation.resume(
                                Result.success(
                                    PartnerLogController.log(
                                        SETUP_SUCCEEDED
                                    )
                                )
                            )
                        }

                        override fun onError(exception: VungleException) {
                            PartnerLogController.log(SETUP_FAILED, "Error: $exception")
                            continuation.resume(
                                Result.failure(
                                    HeliumAdException(
                                        getHeliumError(
                                            exception
                                        )
                                    )
                                )
                            )
                        }

                        override fun onAutoCacheAdAvailable(placementId: String) {}
                    }, VungleSettings.Builder().apply {
                        if (disableBannerRefresh) disableBannerRefresh()
                    }.build())
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing App ID.")
                continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)))
            }
        }
    }

    /**
     * Notify Vungle of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String?
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        Vungle.updateCCPAStatus(
            if (hasGrantedCcpaConsent) {
                Consent.OPTED_IN
            } else {
                Consent.OPTED_OUT
            }
        )
    }

    /**
     * Notify the Vungle SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        if (applies == true) {
            val consent: Consent =
                if (gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED) {
                    Consent.OPTED_IN
                } else {
                    Consent.OPTED_OUT
                }
            Vungle.updateConsentStatus(consent, "")
        }
    }

    /**
     * Notify Vungle of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        // NO-OP. Vungle does not have an API for setting COPPA.
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return mapOf("bid_token" to Vungle.getAvailableBidTokens(context))
    }

    /**
     * Attempt to load a Vungle ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.BANNER -> {
                loadBannerAd(request, partnerAdListener)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                loadFullscreenAd(request, partnerAdListener)
            }
        }
    }

    /**
     * Attempt to show the currently loaded Vungle ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        val listener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> showFullscreenAd(partnerAd, listener)
        }
    }

    /**
     * Discard unnecessary Vungle ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        listeners.remove(partnerAd.request.heliumPlacement)
        adms.remove(partnerAd.request.partnerPlacement)

        return when (partnerAd.request.format) {
            /**
             * Only invalidate banner ads.
             * For fullscreen ads, since Vungle does not provide an ad in the load callback, we don't
             * have an ad in PartnerAd to invalidate.
             */
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Create a [PartnerAd] instance to hold a Vungle ad.
     *
     * @param ad The Vungle ad to hold.
     * @param request The original [PartnerAdLoadRequest] instance that was used to load the ad.
     *
     * @return A [PartnerAd] instance.
     */
    private fun createPartnerAd(ad: Any?, request: PartnerAdLoadRequest): PartnerAd {
        return PartnerAd(
            ad = ad,
            details = emptyMap(),
            request = request
        )
    }

    /**
     * Attempt to load a Vungle banner ad.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadBannerAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val bannerAdConfig = BannerAdConfig()
        bannerAdConfig.adSize = getVungleBannerSize(request.size)
        bannerAdConfig.setMuted(mute)

        if (showingBanners.contains(request.partnerPlacement)) {
            PartnerLogController.log(
                LOAD_FAILED,
                "Vungle is already showing a banner. Failing the banner load for ${request.heliumPlacement}"
            )
            return Result.failure(HeliumAdException(HeliumError.HE_LOAD_FAILURE_SHOW_IN_PROGRESS))
        }

        return suspendCoroutine { continuation ->
            Banners.loadBanner(
                request.partnerPlacement,
                request.adm,
                bannerAdConfig,
                object : LoadAdCallback {
                    override fun onAdLoad(placementId: String) {
                        if (!Banners.canPlayAd(
                                placementId,
                                request.adm,
                                bannerAdConfig.adSize
                            )
                        ) {
                            PartnerLogController.log(LOAD_FAILED, "Placement: $placementId.")
                            continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_LOAD_FAILURE_MISMATCHED_AD_PARAMS)))
                            return
                        }

                        val ad = Banners.getBanner(
                            placementId,
                            request.adm,
                            bannerAdConfig,
                            object : PlayAdCallback {
                                override fun creativeId(creativeId: String) {
                                    // Ignored.
                                }

                                override fun onAdStart(id: String) {
                                    // Ignored.
                                }

                                override fun onAdEnd(
                                    id: String,
                                    completed: Boolean,
                                    isCTAClicked: Boolean
                                ) {
                                    // Ignored. Deprecated.
                                }

                                override fun onAdEnd(id: String) {
                                    showingBanners.remove(request.partnerPlacement)
                                }

                                override fun onAdClick(id: String) {
                                    PartnerLogController.log(DID_CLICK)
                                    listener.onPartnerAdClicked(
                                        createPartnerAd(null, request)
                                    )
                                }

                                override fun onAdRewarded(id: String) {
                                    // Ignored. Not supporting rewarded banner ads.
                                }

                                override fun onAdLeftApplication(id: String) {
                                    // Ignored.
                                }

                                override fun onError(
                                    placementReferenceId: String,
                                    exception: VungleException
                                ) {
                                    showingBanners.remove(request.partnerPlacement)
                                    PartnerLogController.log(
                                        SHOW_FAILED,
                                        "Placement: $placementId. Error code: ${exception.exceptionCode}. " +
                                                "Message: ${exception.message}"
                                    )
                                }

                                override fun onAdViewed(id: String) {
                                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                                    showingBanners.add(request.partnerPlacement)
                                    listener.onPartnerAdImpression(
                                        createPartnerAd(null, request)
                                    )
                                }
                            })

                        ad?.let {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            continuation.resume(
                                Result.success(createPartnerAd(it, request))
                            )
                        } ?: run {
                            PartnerLogController.log(LOAD_FAILED, "Placement: $placementId.")
                            continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_LOAD_FAILURE_UNKNOWN)))
                        }
                    }

                    override fun onError(placementId: String, exception: VungleException) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: $placementId. Error code: ${exception.exceptionCode}. " +
                                    "Message: ${exception.message}"
                        )
                        continuation.resume(
                            Result.failure(
                                HeliumAdException(
                                    getHeliumError(
                                        exception
                                    )
                                )
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Convert a Helium banner size into the corresponding Vungle banner size.
     *
     * @param size The Helium banner size.
     *
     * @return The Vungle banner size.
     */
    private fun getVungleBannerSize(size: Size?): AdConfig.AdSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> AdConfig.AdSize.BANNER
                it in 90 until 250 -> AdConfig.AdSize.BANNER_LEADERBOARD
                it >= 250 -> AdConfig.AdSize.VUNGLE_MREC
                else -> AdConfig.AdSize.BANNER
            }
        } ?: AdConfig.AdSize.BANNER
    }

    /**
     * Attempt to load a Vungle fullscreen ad. This method supports both interstitial and rewarded ads.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadFullscreenAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = listener
        adms[request.partnerPlacement] = request.adm

        val adConfig = AdConfig()
        adConfig.setMuted(mute)
        adConfig.setTransitionAnimationEnabled(transitionAnimationEnabled)
        adConfig.setBackButtonImmediatelyEnabled(backBtnImmediatelyEnabled)
        adConfig.setImmersiveMode(immersiveMode)
        adOrientation?.let { adConfig.adOrientation = it }

        return suspendCoroutine { continuation ->
            Vungle.loadAd(
                request.partnerPlacement,
                request.adm,
                adConfig,
                object : LoadAdCallback {
                    override fun onAdLoad(id: String) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(
                            Result.success(createPartnerAd(null, request))
                        )
                    }

                    override fun onError(placementId: String?, exception: VungleException?) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: $placementId. Error code: ${exception?.exceptionCode}. " +
                                    "Message: ${exception?.message}"
                        )
                        continuation.resume(
                            Result.failure(
                                HeliumAdException(
                                    getHeliumError(
                                        exception
                                    )
                                )
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Attempt to show a Vungle fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the Vungle ad to be shown.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun showFullscreenAd(
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        val adm = adms.remove(partnerAd.request.partnerPlacement)

        return suspendCoroutine { continuation ->
            if (Vungle.canPlayAd(partnerAd.request.partnerPlacement, adm)) {
                Vungle.playAd(
                    partnerAd.request.partnerPlacement,
                    adm,
                    null,
                    object : PlayAdCallback {
                        override fun creativeId(creativeId: String) {
                            // Ignored.
                        }

                        override fun onAdStart(id: String) {
                            PartnerLogController.log(SHOW_SUCCEEDED)
                            continuation.resume(Result.success(partnerAd))
                        }

                        override fun onAdEnd(
                            id: String,
                            completed: Boolean,
                            isCTAClicked: Boolean
                        ) {
                            // Ignored. Deprecated.
                        }

                        override fun onAdEnd(id: String) {
                            PartnerLogController.log(DID_DISMISS)
                            listener?.onPartnerAdDismissed(partnerAd, null)
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onAdEnd for Vungle adapter. Listener is null."
                                )
                        }

                        override fun onAdClick(id: String) {
                            PartnerLogController.log(DID_CLICK)
                            listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onAdClick for Vungle adapter. Listener is null."
                            )
                        }

                        override fun onAdRewarded(id: String) {
                            PartnerLogController.log(DID_REWARD)
                            listener?.onPartnerAdRewarded(partnerAd, Reward(0, ""))
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onAdRewarded for Vungle adapter. Listener is null."
                                )
                        }

                        override fun onAdLeftApplication(id: String) {}

                        override fun onError(
                            placementReferenceId: String,
                            exception: VungleException
                        ) {
                            PartnerLogController.log(
                                SHOW_FAILED,
                                "Vungle failed to show the fullscreen ad for placement " +
                                        "$placementReferenceId. Error code: ${exception.exceptionCode}. " +
                                        "Message: ${exception.message}"
                            )

                            continuation.resume(
                                Result.failure(
                                    HeliumAdException(
                                        getHeliumError(
                                            exception
                                        )
                                    )
                                )
                            )
                        }

                        override fun onAdViewed(id: String) {
                            PartnerLogController.log(DID_TRACK_IMPRESSION)
                            listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onAdViewed for Vungle adapter. Listener is null."
                            )
                        }
                    })
            } else {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Vungle failed to show the fullscreen ad for placement " +
                            "${partnerAd.request.partnerPlacement}."
                )
                continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_SHOW_FAILURE_UNKNOWN)))
            }
        }
    }

    /**
     * Attempt to the destroy the current Vungle banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let { ad ->
            if (ad is VungleBanner) ad.destroyAd()

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(HeliumAdException(HeliumError.HE_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Convert a given Vungle exception into a [HeliumError].
     *
     * @param exception The Vungle exception to convert.
     *
     * @return The corresponding [HeliumError].
     */
    private fun getHeliumError(exception: VungleException?) = when (exception?.exceptionCode) {
        NO_SERVE, AD_FAILED_TO_DOWNLOAD, NO_AUTO_CACHED_PLACEMENT -> HeliumError.HE_LOAD_FAILURE_NO_FILL
        SERVER_ERROR, SERVER_TEMPORARY_UNAVAILABLE, ASSET_DOWNLOAD_ERROR -> HeliumError.HE_AD_SERVER_ERROR
        NETWORK_ERROR, NETWORK_UNREACHABLE -> HeliumError.HE_NO_CONNECTIVITY
        VUNGLE_NOT_INTIALIZED -> HeliumError.HE_INITIALIZATION_FAILURE_UNKNOWN
        MISSING_REQUIRED_ARGUMENTS_FOR_INIT -> HeliumError.HE_INITIALIZATION_FAILURE_INVALID_CREDENTIALS
        PLACEMENT_NOT_FOUND -> HeliumError.HE_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT
        else -> HeliumError.HE_PARTNER_ERROR
    }
}
