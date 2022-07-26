package com.chartboost.helium.vungleadapter

import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import com.vungle.warren.*
import com.vungle.warren.Vungle.Consent
import com.vungle.warren.error.VungleException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 *  The Helium Vungle Adapter.
 */
class VungleAdapter : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private const val TAG = "[VungleAdapter]"

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
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies = false

    /**
     * Track the Vungle ad markup for fullscreen ad load --> show cycle, keyed by the Vungle placement ID.
     */
    private var adms: MutableMap<String, String?> = mutableMapOf()

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
        get() = BuildConfig.VERSION_NAME

    /**
     * Get the Vungle adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.VERSION_NAME

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
        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials[APP_ID_KEY]?.let { appId ->
                Vungle.init(appId, context.applicationContext, object : InitCallback {
                    override fun onSuccess() {
                        Plugin.addWrapperInfo(
                            VungleApiClient.WrapperFramework.vunglehbs,
                            adapterVersion
                        )

                        continuation.resume(Result.success(LogController.i("$TAG Vungle successfully initialized.")))
                    }

                    override fun onError(exception: VungleException) {
                        LogController.e("$TAG Vungle failed to initialize. Error: $exception")
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
                    }

                    override fun onAutoCacheAdAvailable(placementId: String) {}
                })
            } ?: run {
                LogController.e("$TAG Vungle failed to initialize. Missing App ID.")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
            }
        }
    }

    /**
     * Notify Vungle of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        Vungle.updateCCPAStatus(
            if (hasGivenCcpaConsent) {
                Consent.OPTED_IN
            } else {
                Consent.OPTED_OUT
            }
        )
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Notify Vungle of the user's GDPR consent status, if applicable.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies) {
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
        return mapOf("bid_token" to Vungle.getAvailableBidTokens(context))
    }

    /**
     * Attempt to load a Vungle ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
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
        val listener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> Result.success(partnerAd)
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
        listeners.remove(partnerAd.request.heliumPlacement)
        adms.remove(partnerAd.request.partnerPlacement)

        return when (partnerAd.request.format) {
            /**
             * Only invalidate banner ads.
             * For fullscreen ads, since Vungle does not provide an ad in the load callback, we don't
             * have an ad in PartnerAd to invalidate.
             */
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> Result.success(partnerAd)
        }
    }

    /**
     * Create a [PartnerAd] instance to hold a Vungle ad.
     *
     * @param ad The Vungle ad to hold.
     * @param request The original [AdLoadRequest] instance that was used to load the ad.
     *
     * @return A [PartnerAd] instance.
     */
    private fun createPartnerAd(ad: Any?, request: AdLoadRequest): PartnerAd {
        return PartnerAd(
            ad = ad,
            details = emptyMap(),
            request = request
        )
    }

    /**
     * Attempt to load a Vungle banner ad.
     *
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadBannerAd(
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val bannerAdConfig = BannerAdConfig()
        bannerAdConfig.adSize = getVungleBannerSize(request.size)

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
                            LogController.e("$TAG Vungle failed to load banner ad for placement $placementId.")
                            continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
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
                                    // Ignored.
                                }

                                override fun onAdClick(id: String) {
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
                                    LogController.e(
                                        "$TAG Vungle failed to show the banner ad for placement " +
                                                "$placementId. Error code: ${exception.exceptionCode}. " +
                                                "Message: ${exception.message}"
                                    )
                                }

                                override fun onAdViewed(id: String) {
                                    listener.onPartnerAdImpression(
                                        createPartnerAd(null, request)
                                    )
                                }
                            })

                        ad?.let {
                            continuation.resume(
                                Result.success(createPartnerAd(it, request))
                            )
                        } ?: run {
                            LogController.e("$TAG Vungle failed to load a banner ad for placement $placementId.")
                            continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                        }
                    }

                    override fun onError(placementId: String, exception: VungleException) {
                        LogController.e(
                            "$TAG Vungle failed to load a banner ad for placement " +
                                    "$placementId. Error code: ${exception.exceptionCode}. " +
                                    "Message: ${exception.message}"
                        )
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
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
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadFullscreenAd(
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = listener
        adms[request.partnerPlacement] = request.adm

        return suspendCoroutine { continuation ->
            Vungle.loadAd(
                request.partnerPlacement,
                request.adm,
                null,
                object : LoadAdCallback {
                    override fun onAdLoad(id: String) {
                        continuation.resume(
                            Result.success(createPartnerAd(null, request))
                        )
                    }

                    override fun onError(placementId: String?, exception: VungleException?) {
                        LogController.e(
                            "$TAG Vungle failed to load fullscreen ad for placement " +
                                    "$placementId. Error code: ${exception?.exceptionCode}. " +
                                    "Message: ${exception?.message}"
                        )
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
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
                            listener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.e(
                                "Unable to fire onAdEnd for Vungle adapter. Listener is null."
                            )
                        }

                        override fun onAdClick(id: String) {
                            listener?.onPartnerAdClicked(partnerAd) ?: LogController.e(
                                "Unable to fire onAdClick for Vungle adapter. Listener is null."
                            )
                        }

                        override fun onAdRewarded(id: String) {
                            listener?.onPartnerAdRewarded(partnerAd, Reward(0, ""))
                                ?: LogController.e(
                                    "Unable to fire onAdRewarded for Vungle adapter. Listener is null."
                                )
                        }

                        override fun onAdLeftApplication(id: String) {}

                        override fun onError(
                            placementReferenceId: String,
                            exception: VungleException
                        ) {
                            LogController.e(
                                "$TAG Vungle failed to show the fullscreen ad for placement " +
                                        "$placementReferenceId. Error code: ${exception.exceptionCode}. " +
                                        "Message: ${exception.message}"
                            )

                            continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                        }

                        override fun onAdViewed(id: String) {
                            listener?.onPartnerAdImpression(partnerAd) ?: LogController.e(
                                "Unable to fire onAdViewed for Vungle adapter. Listener is null."
                            )
                        }
                    })
            } else {
                LogController.e(
                    "$TAG Vungle failed to show the fullscreen ad for placement " +
                            "${partnerAd.request.partnerPlacement}."
                )
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
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
            Result.success(partnerAd)
        } ?: run {
            LogController.e("$TAG Failed to destroy Vungle banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }
}
