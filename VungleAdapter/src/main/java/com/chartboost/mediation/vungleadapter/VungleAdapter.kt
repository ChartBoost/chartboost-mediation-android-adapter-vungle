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
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_NOT_APPLICABLE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.*
import com.chartboost.mediation.vungleadapter.VungleAdapterConfiguration.adOrientation
import com.chartboost.mediation.vungleadapter.VungleAdapterConfiguration.adapterVersion
import com.chartboost.mediation.vungleadapter.VungleAdapterConfiguration.backBtnImmediatelyEnabled
import com.vungle.ads.*
import com.vungle.ads.VungleError.Companion.AD_FAILED_TO_DOWNLOAD
import com.vungle.ads.VungleError.Companion.ASSET_DOWNLOAD_ERROR
import com.vungle.ads.VungleError.Companion.INVALID_APP_ID
import com.vungle.ads.VungleError.Companion.NETWORK_ERROR
import com.vungle.ads.VungleError.Companion.NETWORK_UNREACHABLE
import com.vungle.ads.VungleError.Companion.NO_SERVE
import com.vungle.ads.VungleError.Companion.PLACEMENT_NOT_FOUND
import com.vungle.ads.VungleError.Companion.SDK_NOT_INITIALIZED
import com.vungle.ads.VungleError.Companion.SERVER_RETRY_ERROR
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
                NO_SERVE, AD_FAILED_TO_DOWNLOAD -> ChartboostMediationError.LoadError.NoFill
                SERVER_RETRY_ERROR, ASSET_DOWNLOAD_ERROR -> ChartboostMediationError.OtherError.AdServerError
                NETWORK_ERROR, NETWORK_UNREACHABLE -> ChartboostMediationError.OtherError.NoConnectivity
                SDK_NOT_INITIALIZED -> ChartboostMediationError.InitializationError.Unknown
                INVALID_APP_ID -> ChartboostMediationError.InitializationError.InvalidCredentials
                PLACEMENT_NOT_FOUND -> ChartboostMediationError.LoadError.InvalidPartnerPlacement
                else -> ChartboostMediationError.OtherError.PartnerError
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
     * The Vungle adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = VungleAdapterConfiguration

    /**
     * Initialize the Vungle SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Vungle.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
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

                                PartnerLogController.log(SETUP_SUCCEEDED)
                                resumeOnce(Result.success(emptyMap()))
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
                        ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials),
                    ),
                )
            }
        }
    }

    /**
     * Notify Vungle if the user is underage.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is underage, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        VunglePrivacySettings.setCOPPAStatus(isUserUnderage)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val token = VungleAds.getBiddingToken(context) ?: ""

        PartnerLogController.log(if (token.isNotEmpty()) BIDDER_INFO_FETCH_SUCCEEDED else BIDDER_INFO_FETCH_FAILED)
        return Result.success(mapOf("bid_token" to token))
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

        return when (request.format) {
            PartnerAdFormats.BANNER -> {
                loadBannerAd(context, request, partnerAdListener)
            }

            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL -> {
                loadFullscreenAd(context, request, partnerAdListener)
            }

            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
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

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            PartnerAdFormats.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }

            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL ->
                showFullscreenAd(partnerAd)

            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(
                    ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat),
                )
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

        return when (partnerAd.request.format) {
            /**
             * Only invalidate banner ads.
             * For fullscreen ads, since Vungle does not provide an ad in the load callback, we don't
             * have an ad in PartnerAd to invalidate.
             */
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.let {
            if (VungleAdapterConfiguration.isGdprStatusOverridden) {
                return@let
            }
            if (it == ConsentValues.DOES_NOT_APPLY) {
                PartnerLogController.log(GDPR_NOT_APPLICABLE)
                return@let
            }

            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )

            VunglePrivacySettings.setGDPRStatus(
                optIn = it == ConsentValues.GRANTED,
                consentMessageVersion = "",
            )
        }

        consents[ConsentKeys.USP]?.let {
            if (VungleAdapterConfiguration.isCcpaStatusOverridden) {
                return@let
            }
            val hasGrantedUspConsent = ConsentManagementPlatform.getUspConsentFromUspString(it)
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )

            VunglePrivacySettings.setCCPAStatus(hasGrantedUspConsent)
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
                    getVungleBannerSize(request.bannerSize?.size),
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
                PartnerAdFormats.INTERSTITIAL -> loadVungleFullScreenAd(InterstitialAd(context, request.partnerPlacement, adConfig))
                PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL ->
                    loadVungleFullScreenAd(
                        RewardedAd(context, request.partnerPlacement, adConfig),
                    )
                else -> {
                    PartnerLogController.log(LOAD_FAILED)
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(
                                ChartboostMediationError.LoadError.UnsupportedAdFormat,
                            ),
                        ),
                    )
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
                                ChartboostMediationError.ShowError.AdNotFound,
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
                                    ChartboostMediationError.ShowError.AdNotReady,
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
                                ChartboostMediationError.ShowError.WrongResourceType,
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }
}
