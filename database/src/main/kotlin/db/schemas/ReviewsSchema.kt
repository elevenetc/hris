package org.jetbrains.hris.db.schemas

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

enum class ReviewStatus { DRAFT, SUBMITTED }

object Reviews : LongIdTable(name = "review") {
    val employeeId =
        reference("employee_id", Employees, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val reviewerId =
        reference("reviewer_id", Employees, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val reviewDate = timestamp("review_date")
    val performanceScore = integer("score")
    val softSkillsScore = integer("soft_skills_score")
    val independenceScore = integer("independence_score")
    val aspirationForGrowthScore = integer("aspiration_for_growth_score")
    val notes = text("notes").nullable()
    val status = enumerationByName<ReviewStatus>("status", length = 32).default(ReviewStatus.DRAFT)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index(false, employeeId, reviewDate)
        reviewerId.index("review_reviewer_idx")
    }
}