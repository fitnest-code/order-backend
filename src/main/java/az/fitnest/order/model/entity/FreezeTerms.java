package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "freeze_terms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "html_content_az", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String htmlContentAz = "";

    @Column(name = "html_content_en", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String htmlContentEn = "";

    @Column(name = "html_content_ru", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String htmlContentRu = "";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
