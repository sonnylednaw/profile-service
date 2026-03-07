package mayson.profileservice.repository;

import mayson.profileservice.jpa.RecurringCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringCostRepository extends JpaRepository<RecurringCost, Long> {
    List<RecurringCost> findAllByUserIdOrderByIdAsc(String userId);
    void deleteAllByUserId(String userId);
}
