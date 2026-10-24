package com.pos.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A place stock is kept within a branch. Sales take stock from the branch's selling storage. */
@Entity
@Table(name = "storages")
@Getter
@Setter
@NoArgsConstructor
public class Storage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean selling;

    @Column(nullable = false)
    private boolean active = true;

    public Storage(Branch branch, String name, boolean selling) {
        this.branch = branch;
        this.name = name;
        this.selling = selling;
    }
}
