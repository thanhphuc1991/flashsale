package com.flashsale.flashsale.repository;

import com.flashsale.flashsale.domain.FlashSaleWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FlashSaleWindowRepository extends JpaRepository<FlashSaleWindow, Long> {

    @Query("SELECT w FROM FlashSaleWindow w WHERE :now >= w.startTime AND :now < w.endTime AND w.status <> 'CANCELLED'")
    List<FlashSaleWindow> findActiveWindows(@Param("now") Instant now);
}
