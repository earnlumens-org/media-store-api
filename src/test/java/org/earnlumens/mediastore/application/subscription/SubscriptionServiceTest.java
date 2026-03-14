package org.earnlumens.mediastore.application.subscription;

import org.earnlumens.mediastore.domain.subscription.model.Subscription;
import org.earnlumens.mediastore.domain.subscription.repository.SubscriptionRepository;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private static final String TENANT = "earnlumens";
    private static final String SUBSCRIBER_ID = "user-1";
    private static final String TARGET_ID = "user-2";

    private User subscriber;
    private User target;

    @BeforeEach
    void setUp() {
        subscriber = new User();
        subscriber.setId("mongo-id-1");
        subscriber.setOauthUserId(SUBSCRIBER_ID);
        subscriber.setUsername("subscriber");
        subscriber.setDisplayName("Sub User");
        subscriber.setProfileImageUrl("https://example.com/avatar1.jpg");

        target = new User();
        target.setId("mongo-id-2");
        target.setOauthUserId(TARGET_ID);
        target.setUsername("creator");
        target.setDisplayName("Creator User");
        target.setProfileImageUrl("https://example.com/avatar2.jpg");
    }

    @Test
    void subscribe_createsNewSubscription() {
        when(subscriptionRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(TENANT, SUBSCRIBER_ID, TARGET_ID))
                .thenReturn(false);
        when(userRepository.findByOauthUserId(SUBSCRIBER_ID)).thenReturn(Optional.of(subscriber));
        when(userRepository.findByOauthUserId(TARGET_ID)).thenReturn(Optional.of(target));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = subscriptionService.subscribe(TENANT, SUBSCRIBER_ID, TARGET_ID);

        assertTrue(result);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertEquals(TENANT, saved.getTenantId());
        assertEquals(SUBSCRIBER_ID, saved.getSubscriberId());
        assertEquals(TARGET_ID, saved.getTargetUserId());
        assertEquals("subscriber", saved.getSubscriberUsername());
        assertEquals("creator", saved.getTargetUsername());
    }

    @Test
    void subscribe_idempotent_returnsFalseIfAlreadySubscribed() {
        when(subscriptionRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(TENANT, SUBSCRIBER_ID, TARGET_ID))
                .thenReturn(true);

        boolean result = subscriptionService.subscribe(TENANT, SUBSCRIBER_ID, TARGET_ID);

        assertFalse(result);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void subscribe_selfSubscription_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> subscriptionService.subscribe(TENANT, SUBSCRIBER_ID, SUBSCRIBER_ID));
    }

    @Test
    void unsubscribe_removesExisting() {
        Subscription existing = new Subscription();
        existing.setId("sub-123");
        existing.setTenantId(TENANT);
        existing.setSubscriberId(SUBSCRIBER_ID);
        existing.setTargetUserId(TARGET_ID);

        when(subscriptionRepository.findByTenantIdAndSubscriberIdAndTargetUserId(TENANT, SUBSCRIBER_ID, TARGET_ID))
                .thenReturn(Optional.of(existing));

        boolean result = subscriptionService.unsubscribe(TENANT, SUBSCRIBER_ID, TARGET_ID);

        assertTrue(result);
        verify(subscriptionRepository).deleteByTenantIdAndId(TENANT, "sub-123");
    }

    @Test
    void unsubscribe_idempotent_returnsFalseIfNotSubscribed() {
        when(subscriptionRepository.findByTenantIdAndSubscriberIdAndTargetUserId(TENANT, SUBSCRIBER_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        boolean result = subscriptionService.unsubscribe(TENANT, SUBSCRIBER_ID, TARGET_ID);

        assertFalse(result);
        verify(subscriptionRepository, never()).deleteByTenantIdAndId(any(), any());
    }

    @Test
    void isSubscribed_returnsTrue() {
        when(subscriptionRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(TENANT, SUBSCRIBER_ID, TARGET_ID))
                .thenReturn(true);

        assertTrue(subscriptionService.isSubscribed(TENANT, SUBSCRIBER_ID, TARGET_ID));
    }

    @Test
    void getSubscriberCount_delegatesToRepository() {
        when(subscriptionRepository.countByTenantIdAndTargetUserId(TENANT, TARGET_ID)).thenReturn(42L);

        assertEquals(42L, subscriptionService.getSubscriberCount(TENANT, TARGET_ID));
    }

    @Test
    void findSubscribedTargetIds_delegatesToRepository() {
        List<String> targetIds = List.of("a", "b", "c");
        when(subscriptionRepository.findSubscribedTargetIds(TENANT, SUBSCRIBER_ID, targetIds))
                .thenReturn(Set.of("a", "c"));

        Set<String> result = subscriptionService.findSubscribedTargetIds(TENANT, SUBSCRIBER_ID, targetIds);

        assertEquals(Set.of("a", "c"), result);
    }
}
