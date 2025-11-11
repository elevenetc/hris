package org.jetbrains.hris.review

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.*
import org.jetbrains.hris.db.initDatabase
import org.jetbrains.hris.db.schemas.ReviewStatus
import org.jetbrains.hris.employee.EmployeeRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.datetime.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
class ReviewRepositoryTest {

    private lateinit var postgres: PostgreSQLContainer<Nothing>

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )

        initDatabase()
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        transaction {
            exec(DatabaseTables.truncateEmployeesAndReviews())
        }
    }

    @Test
    fun `createReview creates a review in DRAFT status`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewDate = Instant.parse("2025-01-15T10:00:00Z")
        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = reviewDate,
                performanceScore = 8,
                softSkillsScore = 7,
                independenceScore = 9,
                aspirationForGrowthScore = 8,
                notes = "Great work!"
            )
        )

        val review = reviewRepo.getReviewById(reviewId)
        assertNotNull(review)
        assertEquals(employee, review.employeeId)
        assertEquals(reviewer, review.reviewerId)
        assertEquals(reviewDate.toString(), review.reviewDate)
        assertEquals(8, review.performanceScore)
        assertEquals(7, review.softSkillsScore)
        assertEquals(9, review.independenceScore)
        assertEquals(8, review.aspirationForGrowthScore)
        assertEquals("Great work!", review.notes)
        assertEquals(ReviewStatus.DRAFT.name, review.status)
    }

    @Test
    fun `createReview validates scores are in range 1-10`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewDate = Instant.parse("2025-01-15T10:00:00Z")

        // Test score 0 (below range)
        assertThrows<IllegalArgumentException> {
            reviewRepo.createReview(
                CreateReviewRequest(
                    employeeId = employee,
                    reviewerId = reviewer,
                    reviewDate = reviewDate,
                    performanceScore = 0,
                    softSkillsScore = 7,
                    independenceScore = 9,
                    aspirationForGrowthScore = 8
                )
            )
        }

        // Test score 11 (above range)
        assertThrows<IllegalArgumentException> {
            reviewRepo.createReview(
                CreateReviewRequest(
                    employeeId = employee,
                    reviewerId = reviewer,
                    reviewDate = reviewDate,
                    performanceScore = 8,
                    softSkillsScore = 11,
                    independenceScore = 9,
                    aspirationForGrowthScore = 8
                )
            )
        }
    }

    @Test
    fun `updateReview updates DRAFT review successfully`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 5,
                softSkillsScore = 5,
                independenceScore = 5,
                aspirationForGrowthScore = 5,
                notes = "Initial draft"
            )
        )

        val success = reviewRepo.updateReview(
            reviewId,
            UpdateReviewRequest(
                performanceScore = 8,
                softSkillsScore = 9,
                notes = "Updated notes"
            )
        )

        assertTrue(success)

        val updated = reviewRepo.getReviewById(reviewId)
        assertNotNull(updated)
        assertEquals(8, updated.performanceScore)
        assertEquals(9, updated.softSkillsScore)
        assertEquals(5, updated.independenceScore) // unchanged
        assertEquals(5, updated.aspirationForGrowthScore) // unchanged
        assertEquals("Updated notes", updated.notes)
        assertEquals(ReviewStatus.DRAFT.name, updated.status)
    }

    @Test
    fun `updateReview fails for SUBMITTED review`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 7,
                independenceScore = 9,
                aspirationForGrowthScore = 8
            )
        )

        // Submit the review
        reviewRepo.submitReview(reviewId)

        // Try to update - should fail
        val success = reviewRepo.updateReview(
            reviewId,
            UpdateReviewRequest(performanceScore = 10)
        )

        assertFalse(success)

        // Verify score is unchanged
        val review = reviewRepo.getReviewById(reviewId)
        assertEquals(8, review?.performanceScore)
    }

    @Test
    fun `submitReview changes status from DRAFT to SUBMITTED`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 7,
                independenceScore = 9,
                aspirationForGrowthScore = 8
            )
        )

        val success = reviewRepo.submitReview(reviewId)
        assertTrue(success)

        val review = reviewRepo.getReviewById(reviewId)
        assertEquals(ReviewStatus.SUBMITTED.name, review?.status)

        // Try to submit again - should fail
        val secondSubmit = reviewRepo.submitReview(reviewId)
        assertFalse(secondSubmit)
    }

    @Test
    fun `getEmployeeReviews returns reviews sorted by date descending`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val review1 = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-01T10:00:00Z"),
                performanceScore = 7,
                softSkillsScore = 7,
                independenceScore = 7,
                aspirationForGrowthScore = 7
            )
        )

        val review2 = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 8,
                independenceScore = 8,
                aspirationForGrowthScore = 8
            )
        )

        val review3 = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-08T10:00:00Z"),
                performanceScore = 9,
                softSkillsScore = 9,
                independenceScore = 9,
                aspirationForGrowthScore = 9
            )
        )

        val reviews = reviewRepo.getEmployeeReviews(employee)

        assertEquals(3, reviews.size)
        // Should be sorted by date descending (most recent first)
        assertEquals(review2, reviews[0].id)
        assertEquals(review3, reviews[1].id)
        assertEquals(review1, reviews[2].id)
    }

    @Test
    fun `getEmployeeReviews filters by date range`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-01T10:00:00Z"),
                performanceScore = 7,
                softSkillsScore = 7,
                independenceScore = 7,
                aspirationForGrowthScore = 7
            )
        )

        reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 8,
                independenceScore = 8,
                aspirationForGrowthScore = 8
            )
        )

        reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-25T10:00:00Z"),
                performanceScore = 9,
                softSkillsScore = 9,
                independenceScore = 9,
                aspirationForGrowthScore = 9
            )
        )

        // Filter by date range: Jan 10 - Jan 20
        val reviews = reviewRepo.getEmployeeReviews(
            employee,
            fromDate = Instant.parse("2025-01-10T00:00:00Z"),
            toDate = Instant.parse("2025-01-20T23:59:59Z")
        )

        assertEquals(1, reviews.size)
        assertEquals(Instant.parse("2025-01-15T10:00:00Z").toString(), reviews[0].reviewDate)
    }

    @Test
    fun `getEmployeeReviews filters by status`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val review1 = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-01T10:00:00Z"),
                performanceScore = 7,
                softSkillsScore = 7,
                independenceScore = 7,
                aspirationForGrowthScore = 7
            )
        )

        val review2 = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 8,
                independenceScore = 8,
                aspirationForGrowthScore = 8
            )
        )

        // Submit only review1
        reviewRepo.submitReview(review1)

        val draftReviews = reviewRepo.getEmployeeReviews(employee, status = ReviewStatus.DRAFT)
        assertEquals(1, draftReviews.size)
        assertEquals(review2, draftReviews[0].id)

        val submittedReviews = reviewRepo.getEmployeeReviews(employee, status = ReviewStatus.SUBMITTED)
        assertEquals(1, submittedReviews.size)
        assertEquals(review1, submittedReviews[0].id)
    }

    @Test
    fun `getEmployeeReviews supports pagination`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        // Create 5 reviews
        repeat(5) { i ->
            reviewRepo.createReview(
                CreateReviewRequest(
                    employeeId = employee,
                    reviewerId = reviewer,
                    reviewDate = Instant.parse("2025-01-${(i + 1).toString().padStart(2, '0')}T10:00:00Z"),
                    performanceScore = 7,
                    softSkillsScore = 7,
                    independenceScore = 7,
                    aspirationForGrowthScore = 7
                )
            )
        }

        val page1 = reviewRepo.getEmployeeReviews(employee, limit = 2, offset = 0)
        assertEquals(2, page1.size)

        val page2 = reviewRepo.getEmployeeReviews(employee, limit = 2, offset = 2)
        assertEquals(2, page2.size)

        val page3 = reviewRepo.getEmployeeReviews(employee, limit = 2, offset = 4)
        assertEquals(1, page3.size)

        // Verify no overlap
        val allIds = (page1 + page2 + page3).map { it.id }.toSet()
        assertEquals(5, allIds.size)
    }

    @Test
    fun `getReviewsByReviewer returns reviews by reviewer`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee1 = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val employee2 = empRepo.addEmployee("Jane", "Smith", "jane@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Bob", "Manager", "bob@example.com", "Manager", null)

        reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee1,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 8,
                independenceScore = 8,
                aspirationForGrowthScore = 8
            )
        )

        reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee2,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-20T10:00:00Z"),
                performanceScore = 9,
                softSkillsScore = 9,
                independenceScore = 9,
                aspirationForGrowthScore = 9
            )
        )

        val reviews = reviewRepo.getReviewsByReviewer(reviewer)
        assertEquals(2, reviews.size)
    }

    @Test
    fun `deleteReview deletes DRAFT review successfully`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 7,
                independenceScore = 9,
                aspirationForGrowthScore = 8
            )
        )

        val success = reviewRepo.deleteReview(reviewId)
        assertTrue(success)

        val review = reviewRepo.getReviewById(reviewId)
        assertNull(review)
    }

    @Test
    fun `deleteReview fails for SUBMITTED review`() {
        val empRepo = EmployeeRepository()
        val reviewRepo = ReviewRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)
        val reviewer = empRepo.addEmployee("Jane", "Manager", "jane@example.com", "Manager", null)

        val reviewId = reviewRepo.createReview(
            CreateReviewRequest(
                employeeId = employee,
                reviewerId = reviewer,
                reviewDate = Instant.parse("2025-01-15T10:00:00Z"),
                performanceScore = 8,
                softSkillsScore = 7,
                independenceScore = 9,
                aspirationForGrowthScore = 8
            )
        )

        reviewRepo.submitReview(reviewId)

        val success = reviewRepo.deleteReview(reviewId)
        assertFalse(success)

        val review = reviewRepo.getReviewById(reviewId)
        assertNotNull(review) // Still exists
    }
}
