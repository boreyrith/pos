package com.pos.inventory;

import java.time.Instant;
import java.util.List;

import com.pos.catalog.CatalogDtos.UnitQuantity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record StorageDto(Long id, Long branchId, String name, boolean selling, boolean active) {
        static StorageDto from(Storage s) {
            return new StorageDto(s.getId(), s.getBranch().getId(), s.getName(), s.isSelling(), s.isActive());
        }
    }

    public record BranchDto(Long id, String code, String name, String address, boolean active,
                            List<StorageDto> storages) {
    }

    public record BranchRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
                                @NotBlank @Size(max = 100) String name, @Size(max = 255) String address,
                                Boolean active) {
    }

    public record StorageRequest(@NotBlank @Size(max = 80) String name, Boolean selling, Boolean active) {
    }

    /** A storage with its branch, as shown on transfers and history. */
    public record PlaceDto(Long storageId, String storageName, Long branchId, String branchName) {
        static PlaceDto from(Storage s) {
            return new PlaceDto(s.getId(), s.getName(), s.getBranch().getId(), s.getBranch().getName());
        }
    }

    public record MovementDto(Long id, Instant createdAt, MovementType type, AdjustmentReason reason, Long offeringId,
                              String offeringName, String sku, String unit, PlaceDto place, int quantity, int balance,
                              String userName, Long orderId, Long transferId, Long adjustmentId, String adjustmentNumber,
                              Long purchaseOrderId, String receiptNumber, String note) {
        static MovementDto from(StockMovement m) {
            var o = m.getOffering();
            return new MovementDto(m.getId(), m.getCreatedAt(), m.getType(), m.getReason(), o.getId(), o.getName(), o.getSku(),
                    o.getUnitSymbol(), PlaceDto.from(m.getStorage()), m.getQuantity(), m.getBalance(),
                    m.getUser() == null ? null : m.getUser().getFullName(), m.getOrderId(), m.getTransferId(),
                    m.getAdjustmentId(), m.getAdjustment() == null ? null : m.getAdjustment().getAdjustmentNumber(),
                    m.getGoodsReceipt() == null ? null : m.getGoodsReceipt().getPurchaseOrder().getId(),
                    m.getGoodsReceipt() == null ? null : m.getGoodsReceipt().getReceiptNumber(), m.getNote());
        }
    }

    public record TransferLineRequest(@NotNull Long offeringId,
                                      @NotEmpty @Size(max = 20) List<@Valid UnitQuantity> quantities) {
    }

    public record TransferRequest(@NotNull Long fromStorageId, @NotNull Long toStorageId, @Size(max = 255) String note,
                                  @NotEmpty @Size(max = 200) List<@Valid TransferLineRequest> lines) {
    }

    public record TransferLineDto(Long offeringId, String name, String sku, String unit, int quantity) {
        static TransferLineDto from(StockTransferLine l) {
            return new TransferLineDto(l.getOffering().getId(), l.getName(), l.getSku(), l.getUnit(), l.getQuantity());
        }
    }

    public record TransferDto(Long id, String transferNumber, TransferStatus status, PlaceDto from, PlaceDto to,
                              String note, String createdBy, Instant createdAt, String closedBy, Instant closedAt,
                              List<TransferLineDto> lines) {
        static TransferDto from(StockTransfer t) {
            return new TransferDto(t.getId(), t.getTransferNumber(), t.getStatus(), PlaceDto.from(t.getFromStorage()),
                    PlaceDto.from(t.getToStorage()), t.getNote(), t.getCreatedBy().getFullName(), t.getCreatedAt(),
                    t.getClosedBy() == null ? null : t.getClosedBy().getFullName(), t.getClosedAt(),
                    t.getLines().stream().map(TransferLineDto::from).toList());
        }
    }
}
