package com.pos.marketing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SegmentRepository extends JpaRepository<Segment, Long> {

    List<Segment> findAllByOrderByNameAsc();
}
