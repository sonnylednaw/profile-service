package mayson.profileservice.repository;

import mayson.profileservice.jpa.AssistantMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssistantMemoryRepository extends JpaRepository<AssistantMemory, Long> {
    Optional<AssistantMemory> findByUserId(String userId);
}
