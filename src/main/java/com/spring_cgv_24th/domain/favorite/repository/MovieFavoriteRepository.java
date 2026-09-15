package com.spring_cgv_24th.domain.favorite.repository;

import com.spring_cgv_24th.domain.favorite.entity.MovieFavorite;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieFavoriteRepository extends JpaRepository<MovieFavorite, Long> {

    boolean existsByMember_IdAndMovie_Id(Long memberId, Long movieId);

    Optional<MovieFavorite> findByMember_IdAndMovie_Id(Long memberId, Long movieId);
}
