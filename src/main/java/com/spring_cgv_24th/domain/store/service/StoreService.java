package com.spring_cgv_24th.domain.store.service;

import com.spring_cgv_24th.domain.store.dto.ProductResDTO;
import com.spring_cgv_24th.domain.store.dto.TheaterStockResDTO;
import com.spring_cgv_24th.domain.store.entity.TheaterStock;
import com.spring_cgv_24th.domain.store.repository.ProductRepository;
import com.spring_cgv_24th.domain.store.repository.TheaterStockRepository;
import com.spring_cgv_24th.domain.theater.repository.TheaterRepository;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final ProductRepository productRepository;
    private final TheaterStockRepository theaterStockRepository;
    private final TheaterRepository theaterRepository;

    public List<ProductResDTO> getProducts() {
        return productRepository.findAll(Sort.by("id")).stream()
                .map(ProductResDTO::from)
                .toList();
    }

    public List<TheaterStockResDTO> getTheaterProducts(Long theaterId) {
        if (!theaterRepository.existsById(theaterId)) {
            throw new CustomException(ErrorCode.THEATER_NOT_FOUND);
        }

        List<TheaterStock> stocks = theaterStockRepository
                .findAllByTheater_IdOrderByProduct_IdAsc(theaterId);
        if (stocks.size() != productRepository.count()) {
            throw new CustomException(ErrorCode.THEATER_STOCK_NOT_FOUND);
        }

        return stocks.stream()
                .map(TheaterStockResDTO::from)
                .toList();
    }
}
