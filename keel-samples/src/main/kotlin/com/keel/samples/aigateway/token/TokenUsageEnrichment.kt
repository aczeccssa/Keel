package com.keel.samples.aigateway.token

internal data class CustomerSummarySnapshot(
    val customerId: String,
    val email: String,
)

internal fun enrichUsageRecords(
    records: List<TokenUsageRecordView>,
    userEmailsById: Map<String, String>,
    customerSummariesById: Map<String, CustomerSummarySnapshot>,
    channelNamesById: Map<String, String>,
    groupNamesById: Map<String, String>,
): List<TokenUsageRecordView> = records.map { record ->
    val derivedCustomer = when {
        !record.customerId.isNullOrBlank() -> {
            val customerId = record.customerId
            val email = record.customerEmail ?: customerSummariesById[customerId]?.email
            customerId to email
        }
        record.userGroupId == "customer" -> {
            val customer = customerSummariesById[record.userId]
            (customer?.customerId ?: record.userId) to customer?.email
        }
        else -> null
    }
    val routingGroupId = record.routingGroupId
    val resolvedChannelId = record.channelId ?: record.upstreamKeyId

    record.copy(
        userEmail = record.userEmail ?: derivedCustomer?.second ?: userEmailsById[record.userId],
        customerId = record.customerId ?: derivedCustomer?.first,
        customerEmail = record.customerEmail ?: derivedCustomer?.second,
        routingGroupName = record.routingGroupName ?: routingGroupId?.let(groupNamesById::get),
        channelName = record.channelName ?: resolvedChannelId?.let(channelNamesById::get),
    )
}
