package com.bromide.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing for the one-time unlock ([Roll.PRODUCT_ID]). Play keeps the purchase, so
 * it comes back after a reinstall or on a new phone: [refresh] reads it on every start.
 */
object Billing : PurchasesUpdatedListener {
    private var client: BillingClient? = null
    @Volatile private var product: ProductDetails? = null

    fun init(context: Context) {
        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) refresh()
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Read the purchase (restores the unlock) and the price. */
    fun refresh() {
        val c = client ?: return
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, complete = true)
        }
        val query = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(Roll.PRODUCT_ID).setProductType(ProductType.INAPP).build()
        )).build()
        c.queryProductDetailsAsync(query) { result, details ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            product = details.productDetailsList.firstOrNull()
            Roll.setPrice(product?.oneTimePurchaseOfferDetails?.formattedPrice)
        }
    }

    /** Open Play's purchase sheet. False if Play isn't reachable (no product details yet). */
    fun buy(activity: Activity): Boolean {
        val c = client ?: return false
        val p = product ?: run { refresh(); return false }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build()
        )).build()
        return c.launchBillingFlow(activity, params).responseCode == BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> handle(purchases.orEmpty(), complete = false)
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
        }
    }

    /** [complete]: the full list of what this account owns, so a missing unlock means refunded. */
    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val unlock = purchases.filter { Roll.PRODUCT_ID in it.products }
        val bought = unlock.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (bought != null) {
            // Play refunds purchases not acknowledged within 3 days.
            if (!bought.isAcknowledged) {
                client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build()) {}
            }
            Roll.setUnlocked(true)
        } else if (complete && !BuildConfig.DEBUG) {
            // Debug builds aren't signed for Play: keep the unlock set from Settings for testing.
            Roll.setUnlocked(false)
        }
        Roll.setPending(bought == null && unlock.any { it.purchaseState == Purchase.PurchaseState.PENDING })
    }
}
