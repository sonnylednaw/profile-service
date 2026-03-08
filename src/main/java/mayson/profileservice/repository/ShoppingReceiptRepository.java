package mayson.profileservice.repository;

import mayson.profileservice.jpa.ShoppingReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingReceiptRepository extends JpaRepository<ShoppingReceipt, Long> {
    List<ShoppingReceipt> findAllByUserIdOrderByCreatedAtDesc(String userId);
    Optional<ShoppingReceipt> findByIdAndUserId(Long id, String userId);
}
