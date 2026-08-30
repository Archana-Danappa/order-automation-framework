package framework.database;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderDbRecord {
    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private String status;
}
