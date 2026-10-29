package com.meetme.server.shared.application.service.operations

import com.meetme.server.config.OperatorQueryProperties
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OperatorAccessPolicy(
    private val properties: OperatorQueryProperties,
) {
    fun canViewOperations(accountId: UUID?): Boolean = properties.enabled && accountId != null && accountId in properties.operatorUserIds
}
