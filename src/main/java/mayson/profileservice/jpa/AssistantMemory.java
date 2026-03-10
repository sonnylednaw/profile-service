package mayson.profileservice.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_memory", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "preference_summary", columnDefinition = "TEXT")
    private String preferenceSummary;

    @Column(name = "last_topics", length = 500)
    private String lastTopics;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
