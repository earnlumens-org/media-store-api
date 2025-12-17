package org.earnlumens.mediastore.infrastructure.persistence.waitlist.adapter;

import org.earnlumens.mediastore.domain.waitlist.model.Founder;
import org.earnlumens.mediastore.domain.waitlist.model.FounderCountByDate;
import org.earnlumens.mediastore.domain.waitlist.repository.FounderRepository;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FounderEntity;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.mapper.FounderMapper;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.repository.FounderMongoRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class FounderRepositoryImpl implements FounderRepository {

    private final FounderMongoRepository founderMongoRepository;
    private final FounderMapper founderMapper;
    private final MongoTemplate mongoTemplate;

    public FounderRepositoryImpl(
            FounderMongoRepository founderMongoRepository,
            FounderMapper founderMapper,
            MongoTemplate mongoTemplate
    ) {
        this.founderMongoRepository = founderMongoRepository;
        this.founderMapper = founderMapper;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<Founder> findByEmail(String email) {
        Optional<FounderEntity> founderEntity = founderMongoRepository.findByEmail(email);
        return founderEntity.map(founderMapper::toFounder);
    }

    @Override
    public Boolean existsByEmail(String email) {
        return founderMongoRepository.existsByEmail(email);
    }

    @Override
    public long countByEntryDateBetween(LocalDateTime start, LocalDateTime end) {
        return founderMongoRepository.countByEntryDateBetween(start, end);
    }

    @Override
    public Founder save(Founder founder) {
        FounderEntity founderEntity = founderMapper.toFounderEntity(founder);
        return founderMapper.toFounder(founderMongoRepository.save(founderEntity));
    }

    @Override
    public long count() {
        return founderMongoRepository.count();
    }

    @Override
    public List<FounderCountByDate> countFoundersGroupedByEntryDate(LocalDateTime start, LocalDateTime end) {
        MatchOperation matchOperation = Aggregation.match(Criteria.where("entryDate").gte(start).lt(end));

        ProjectionOperation projectToDay = Aggregation.project()
                .andExpression("year(entryDate)").as("year")
                .andExpression("month(entryDate)").as("month")
                .andExpression("dayOfMonth(entryDate)").as("day");

        GroupOperation groupOperation = Aggregation.group("year", "month", "day").count().as("totalFounders");

        SortOperation sortOperation = Aggregation.sort(Sort.Direction.ASC, "year", "month", "day");

        ProjectionOperation projectBackToDate = Aggregation.project()
                .andExpression("{ $dateFromParts: { 'year': '$_id.year', 'month': '$_id.month', 'day': '$_id.day' } }")
                .as("entryDate")
                .andInclude("totalFounders");

        Aggregation aggregation = Aggregation.newAggregation(
                matchOperation,
                projectToDay,
                groupOperation,
                sortOperation,
                projectBackToDate
        );

        AggregationResults<FounderCountByDate> results = mongoTemplate.aggregate(
                aggregation,
                FounderEntity.class,
                FounderCountByDate.class
        );

        return results.getMappedResults();
    }
}
