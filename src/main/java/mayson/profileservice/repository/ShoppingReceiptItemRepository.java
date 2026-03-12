package mayson.profileservice.repository;

import mayson.profileservice.jpa.ShoppingReceipt;
import mayson.profileservice.jpa.ShoppingReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingReceiptItemRepository extends JpaRepository<ShoppingReceiptItem, Long> {
    List<ShoppingReceiptItem> findAllByReceiptOrderByPositionIndexAsc(ShoppingReceipt receipt);
    Optional<ShoppingReceiptItem> findByIdAndReceipt(Long id, ShoppingReceipt receipt);
    void deleteAllByReceipt(ShoppingReceipt receipt);
}
