package org.jetbrains.hris.common.events

/**
 * Base interface for all domain events in the system.
 */
sealed interface ApplicationEvent

// ------------------ Review Events ------------------

data class ReviewSubmittedEvent(
    val reviewId: Long,
    val employeeId: Long,
    val reviewerId: Long
) : ApplicationEvent

data class ReviewReceivedEvent(
    val reviewId: Long,
    val employeeId: Long,
    val reviewerId: Long
) : ApplicationEvent

// ------------------ Employee Events ------------------

data class ManagerChangedEvent(
    val employeeId: Long,
    val oldManagerId: Long?,
    val newManagerId: Long?,
    val employeeName: String?
) : ApplicationEvent

data class NewDirectReportEvent(
    val managerId: Long,
    val employeeId: Long,
    val employeeName: String?
) : ApplicationEvent
