package az.fitnest.order.repository;

import az.fitnest.order.model.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    java.util.List<Order> findByType(String type);

    void deleteByUserId(Long userId);
}
