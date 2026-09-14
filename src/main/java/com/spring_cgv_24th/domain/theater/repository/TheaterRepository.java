package com.spring_cgv_24th.domain.theater.repository;

import com.spring_cgv_24th.domain.theater.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterRepository extends JpaRepository<Theater, Long> {
}
