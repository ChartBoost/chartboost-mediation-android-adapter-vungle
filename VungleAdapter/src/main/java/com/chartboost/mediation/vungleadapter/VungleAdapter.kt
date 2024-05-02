/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.vungleadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.vungle.ads.AdConfig
import com.vungle.ads.BannerAd
import com.vungle.ads.BannerAdListener
import com.vungle.ads.BannerAdSize
import com.vungle.ads.BaseAd
import com.vungle.ads.BaseFullscreenAd
import com.vungle.ads.FullscreenAdListener
import com.vungle.ads.InitializationListener
import com.vungle.ads.InterstitialAd
import com.vungle.ads.RewardedAd
import com.vungle.ads.RewardedAdListener
import com.vungle.ads.VungleAds
import com.vungle.ads.VungleError
import com.vungle.ads.VungleError.Companion.AD_FAILED_TO_DOWNLOAD
import com.vungle.ads.VungleError.Companion.ASSET_DOWNLOAD_ERROR
import com.vungle.ads.VungleError.Companion.INVALID_APP_ID
import com.vungle.ads.VungleError.Companion.NETWORK_ERROR
import com.vungle.ads.VungleError.Companion.NETWORK_UNREACHABLE
import com.vungle.ads.VungleError.Companion.NO_SERVE
import com.vungle.ads.VungleError.Companion.PLACEMENT_NOT_FOUND
import com.vungle.ads.VungleError.Companion.SDK_NOT_INITIALIZED
import com.vungle.ads.VungleError.Companion.SERVER_RETRY_ERROR
import com.vungle.ads.VunglePrivacySettings
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 *  The Chartboost Mediation Vungle Adapter.
 */
class VungleAdapter : PartnerAdapter {
    companion object {
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
                    CUSTOM,
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
                    CUSTOM,
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

        /**
         * Key for parsing the Vungle app ID.
         */
        private const val APP_ID_KEY = "vungle_app_id"

        /**
         * Convert a given Vungle exception into a [ChartboostMediationError].
         *
         * @param vungleError The Vungle error to convert.
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(vungleError: VungleError?) =
            when (vungleError?.code) {
                NO_SERVE, AD_FAILED_TO_DOWNLOAD -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
                SERVER_RETRY_ERROR, ASSET_DOWNLOAD_ERROR -> ChartboostMediationError.CM_AD_SERVER_ERROR
                NETWORK_ERROR, NETWORK_UNREACHABLE -> ChartboostMediationError.CM_NO_CONNECTIVITY
                SDK_NOT_INITIALIZED -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN
                INVALID_APP_ID -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS
                PLACEMENT_NOT_FOUND -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT
                else -> ChartboostMediationError.CM_PARTNER_ERROR
            }

        /**
         * A lambda to call for successful Vungle ad shows.
         */
        internal var onPlaySucceeded: () -> Unit = {}

        /**
         * A lambda to call for failed Vungle ad shows.
         */
        internal var onPlayFailed: (baseAd: BaseAd, error: VungleError) -> Unit =
            { _: BaseAd, _: VungleError -> }
    }

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
        get() = VungleAds.getSdkVersion()

    /**
     * Get the Vungle adapter version.
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
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_VUNGLE_ADAPTER_VERSION

    /**
     * Initialize the Vungle SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Vungle.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(APP_ID_KEY),
            )
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { appId ->
                    VungleAds.init(
                        context,
                        appId,
                        object : InitializationListener {
                            override fun onSuccess() {
                                VungleAds.setIntegrationName(
                                    VungleAds.WrapperFramework.vunglehbs,
                                    adapterVersion,
                                )

                                resumeOnce(Result.success(PartnerLogController.log(SETUP_SUCCEEDED)))
                            }

                            override fun onError(vungleError: VungleError) {
                                PartnerLogController.log(
                                    SETUP_FAILED,
                                    "Error: ${getChartboostMediationError(vungleError)}",
                                )
                                resumeOnce(
                                    Result.failure(
                                        ChartboostMediationAdException(
                                            getChartboostMediationError(vungleError),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing App ID.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS),
                    ),
                )
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
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        VunglePrivacySettings.setCCPAStatus(hasGrantedCcpaConsent)
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
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        if (applies == true) {
            VunglePrivacySettings.setGDPRStatus(
                optIn = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED,
                consentMessageVersion = "",
            )
        }
    }

    /**
     * Set Vungle user's consent value using a boolean.
     * This is for publishers to manually set the consent status.
     * This uses CONSENT_GIVEN for true and CONSENT_DECLINED for false.
     *
     * @param context a context that will be passed to the SharedPreferences to set the user consent.
     * @param applies True if GDPR applies, false otherwise.
     * @param consented whether or not the user has consented.
     */
    fun setGdpr(
        context: Context,
        applies: Boolean?,
        consented: Boolean,
    ) {
        setGdpr(
            context,
            applies,
            if (consented) GdprConsentStatus.GDPR_CONSENT_GRANTED else GdprConsentStatus.GDPR_CONSENT_DENIED,
        )
    }

    /**
     * Notify Vungle of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

        // NO-OP. See: https://support.vungle.com/hc/en-us/articles/360047780372#coppa-implementation-0-2
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
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val token = VungleAds.getBiddingToken(context) ?: ""

        PartnerLogController.log(if (token.isNotEmpty()) BIDDER_INFO_FETCH_SUCCEEDED else BIDDER_INFO_FETCH_FAILED)
        return mapOf("bid_token" to token)
    }

    /**
     * Attempt to load a Vungle ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> {
                loadBannerAd(context, request, partnerAdListener)
            }

            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> {
                loadFullscreenAd(context, request, partnerAdListener)
            }

            else -> {
                if (request.format.key == "rewarded_interstitial") {
                    loadFullscreenAd(context, request, partnerAdListener)
                } else {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
        }
    }

    /**
     * Attempt to show the currently loaded Vungle ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format.key) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER.key, "adaptive_banner" -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }

            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> showFullscreenAd(partnerAd)
            else -> {
                if (partnerAd.request.format.key == "rewarded_interstitial") {
                    showFullscreenAd(partnerAd)
                } else {
                    PartnerLogController.log(SHOW_FAILED)
                    Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT),
                    )
                }
            }
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

        return when (partnerAd.request.format.key) {
            /**
             * Only invalidate banner ads.
             * For fullscreen ads, since Vungle does not provide an ad in the load callback, we don't
             * have an ad in PartnerAd to invalidate.
             */
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
            else -> {
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
    private fun createPartnerAd(
        ad: Any?,
        request: PartnerAdLoadRequest,
    ): PartnerAd {
        return PartnerAd(
            ad = ad,
            details = emptyMap(),
            request = request,
        )
    }

    /**
     * Attempt to load a Vungle banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Vungle needs a nullable adm instead of an empty string for non-programmatic ads.
        val adm = if (request.adm?.isEmpty() == true) null else request.adm

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val vungleBanner =
                BannerAd(
                    context,
                    request.partnerPlacement,
                    getVungleBannerSize(request.size),
                )

            vungleBanner.adListener =
                object : BannerAdListener {
                    override fun onAdClicked(baseAd: BaseAd) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            createPartnerAd(baseAd, request),
                        )
                    }

                    override fun onAdEnd(baseAd: BaseAd) {}

                    override fun onAdFailedToLoad(
                        baseAd: BaseAd,
                        adError: VungleError,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: ${baseAd.placementId}. Error code: ${adError.code}. " +
                                "Message: ${adError.message}",
                        )
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    getChartboostMediationError(adError),
                                ),
                            ),
                        )
                    }

                    override fun onAdFailedToPlay(
                        baseAd: BaseAd,
                        adError: VungleError,
                    ) {
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Placement: ${baseAd.placementId}. Error code: ${adError.code}. " +
                                "Message: ${adError.message}",
                        )
                    }

                    override fun onAdImpression(baseAd: BaseAd) {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener.onPartnerAdImpression(
                            createPartnerAd(baseAd, request),
                        )
                    }

                    override fun onAdLeftApplication(baseAd: BaseAd) {}

                    override fun onAdLoaded(baseAd: BaseAd) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(createPartnerAd(vungleBanner.getBannerView(), request)),
                        )
                    }

                    override fun onAdStart(baseAd: BaseAd) {}
                }

            vungleBanner.load(adm)
        }
    }

    /**
     * Convert a Chartboost Mediation banner size into the corresponding Vungle banner size.
     *
     * @param size The Chartboost Mediation banner size.
     *
     * @return The Vungle banner size.
     */
    private fun getVungleBannerSize(size: Size?): BannerAdSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> BannerAdSize.BANNER
                it in 90 until 250 -> BannerAdSize.BANNER_LEADERBOARD
                it >= 250 -> BannerAdSize.VUNGLE_MREC
                else -> BannerAdSize.BANNER
            }
        } ?: BannerAdSize.BANNER
    }

    /**
     * Attempt to load a Vungle fullscreen ad. This method supports both all fullscreen ads.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullscreenAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Vungle needs a nullable adm instead of an empty string for non-programmatic ads.
        val adm = if (request.adm?.isEmpty() == true) null else request.adm

        val adConfig = AdConfig()
        adConfig.setBackButtonImmediatelyEnabled(backBtnImmediatelyEnabled)
        adOrientation?.let { adConfig.adOrientation = it }

        return suspendCancellableCoroutine { continuation ->
            val continuationRef = WeakReference(continuation)

            fun resumeOnce(result: Result<PartnerAd>) {
                continuationRef.get()?.let {
                    if (it.isActive) {
                        it.resume(result)
                    }
                } ?: run {
                    PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
                }
            }

            fun loadVungleFullScreenAd(fullscreenAd: BaseFullscreenAd) {
                fullscreenAd.adListener =
                    VungleFullScreenAdListener(
                        request = request,
                        listener = listener,
                        continuationRef = continuationRef,
                    )
                fullscreenAd.load(adm)
            }

            when (request.format) {
                AdFormat.INTERSTITIAL -> loadVungleFullScreenAd(InterstitialAd(context, request.partnerPlacement, adConfig))
                AdFormat.REWARDED -> loadVungleFullScreenAd(RewardedAd(context, request.partnerPlacement, adConfig))
                else -> {
                    if (request.format.key == "rewarded_interstitial") {
                        loadVungleFullScreenAd(RewardedAd(context, request.partnerPlacement, adConfig))
                    } else {
                        PartnerLogController.log(LOAD_FAILED)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Attempt to show a Vungle fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the Vungle ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val continuationRef = WeakReference(continuation)

            fun resumeOnce(result: Result<PartnerAd>) {
                continuationRef.get()?.let {
                    if (it.isActive) {
                        it.resume(result)
                    }
                } ?: run {
                    PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
                }
            }

            onPlaySucceeded = {
                PartnerLogController.log(SHOW_SUCCEEDED)
                resumeOnce(Result.success(partnerAd))
            }

            onPlayFailed = { baseAd, adError ->
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Vungle failed to show the fullscreen ad for placement " +
                        "${baseAd.placementId}.",
                )
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(
                                adError,
                            ),
                        ),
                    ),
                )
            }

            when (val ad = partnerAd.ad) {
                null -> {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(
                                ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND,
                            ),
                        ),
                    )
                }

                is BaseFullscreenAd ->
                    if (ad.canPlayAd()) {
                        ad.play()
                    } else {
                        PartnerLogController.log(SHOW_FAILED)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY,
                                ),
                            ),
                        )
                    }

                else -> {
                    PartnerLogController.log(
                        SHOW_FAILED,
                        "Ad is not an instance of InterstitialAd or RewardedAd.",
                    )
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(
                                ChartboostMediationError.CM_SHOW_FAILURE_WRONG_RESOURCE_TYPE,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Create a Vungle fullscreen ad listener.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuationRef A [CancellableContinuation] to notify when the [Result] has succeeded or failed.
     *
     * @return a fullscreen listener to attach to a Vungle ad object.
     */
    private class VungleFullScreenAdListener(
        private val request: PartnerAdLoadRequest,
        private val listener: PartnerAdListener,
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
    ) : FullscreenAdListener, RewardedAdListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onAdClicked(baseAd: BaseAd) {
            PartnerLogController.log(DID_CLICK)
            listener.onPartnerAdClicked(
                PartnerAd(
                    ad = baseAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdEnd(baseAd: BaseAd) {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(
                PartnerAd(
                    ad = baseAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onAdFailedToLoad(
            baseAd: BaseAd,
            adError: VungleError,
        ) {
            PartnerLogController.log(
                LOAD_FAILED,
                "Placement: ${baseAd.placementId}. Error code: ${adError.code}. " +
                    "Message: ${adError.message}",
            )
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        getChartboostMediationError(
                            adError,
                        ),
                    ),
                ),
            )
        }

        override fun onAdFailedToPlay(
            baseAd: BaseAd,
            adError: VungleError,
        ) {
            onPlayFailed(baseAd, adError)
            onPlayFailed = { _, _ -> }
        }

        override fun onAdImpression(baseAd: BaseAd) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            listener.onPartnerAdImpression(
                PartnerAd(
                    ad = baseAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdLeftApplication(baseAd: BaseAd) {}

        override fun onAdLoaded(baseAd: BaseAd) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = baseAd,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdRewarded(baseAd: BaseAd) {
            PartnerLogController.log(DID_REWARD)
            listener.onPartnerAdRewarded(
                PartnerAd(
                    ad = baseAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdStart(baseAd: BaseAd) {
            onPlaySucceeded()
            onPlaySucceeded = {}
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
            if (ad is BannerAd) {
                ad.finishAd()
                ad.adListener = null
            }

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }
}
