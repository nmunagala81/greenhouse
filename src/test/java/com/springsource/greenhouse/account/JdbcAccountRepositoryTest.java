package com.springsource.greenhouse.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.security.encrypt.NoOpPasswordEncoder;
import org.springframework.security.encrypt.StandardStringEncryptor;
import org.springframework.security.encrypt.StringEncryptor;
import org.springframework.test.transaction.TransactionalMethodRule;
import org.springframework.transaction.annotation.Transactional;

import com.springsource.greenhouse.database.GreenhouseTestDatabaseBuilder;

public class JdbcAccountRepositoryTest {
	
	private EmbeddedDatabase db;
	
	private JdbcAccountRepository accountRepository;

	private JdbcTemplate jdbcTemplate;

	private String salt = "5b8bd7612cdab5ed";
	
	private StringEncryptor encryptor;
	
    @Before
    public void setup() {
    	db = new GreenhouseTestDatabaseBuilder().member().connectedAccount().connectedApp().testData(getClass()).getDatabase();
    	jdbcTemplate = new JdbcTemplate(db);
    	encryptor = new StandardStringEncryptor("secret", salt);
    	System.out.println(encryptor.encrypt("345678901"));
    	accountRepository = new JdbcAccountRepository(jdbcTemplate, encryptor, NoOpPasswordEncoder.getInstance(), new StubFileStorage(), "http://localhost:8080/members/{profileKey}");
    }
    
	@After
	public void destroy() {
		if (db != null) {
			db.shutdown();
		}
	}

    @Test
    @Transactional
    public void create() throws EmailAlreadyOnFileException {
    	Person person = new Person("Jack", "Black", "jack@black.com", "foobie", Gender.Male, new LocalDate(1977, 12, 1));
    	Account account = accountRepository.createAccount(person);
    	assertEquals(3L, (long) account.getId());
    	assertEquals("Jack Black", account.getFullName());
    	assertEquals("jack@black.com", account.getEmail());
    	assertEquals("http://localhost:8080/members/3", account.getProfileUrl());
    	assertEquals("http://localhost:8080/resources/profile-pics/male/small.jpg", account.getPictureUrl());
    }
    
    @Test
    public void authenticate() throws UsernameNotFoundException, InvalidPasswordException {
    	Account account = accountRepository.authenticate("kdonald", "password");
    	assertEquals("Keith Donald", account.getFullName());
    }

    @Test(expected=InvalidPasswordException.class)
    public void authenticateInvalidPassword() throws UsernameNotFoundException, InvalidPasswordException {
    	accountRepository.authenticate("kdonald", "bogus");
    }

    @Test
    public void findById() {
    	assertExpectedAccount(accountRepository.findById(1L));
    }
    
    @Test 
    public void findtByEmail() throws Exception {
    	assertExpectedAccount(accountRepository.findByUsername("cwalls@vmware.com"));
    }
    
    @Test
    public void findByUsername() throws Exception {
    	assertExpectedAccount(accountRepository.findByUsername("habuma"));
    }
    
    @Test(expected=UsernameNotFoundException.class)
    public void usernameNotFound() throws Exception {
    	accountRepository.findByUsername("strangerdanger");
    }
        
    @Test(expected=UsernameNotFoundException.class)
    public void usernameNotFoundEmail() throws Exception {
    	accountRepository.findByUsername("stranger@danger.com");
    }

    @Test
    public void markProfilePictureSet() {
    	accountRepository.markProfilePictureSet(1L);
    	assertTrue(jdbcTemplate.queryForObject("select pictureSet from Member where id = ?", Boolean.class, 1L));
    }

    // connected account tests
    
    @Test
    public void connectAccount() throws Exception {
    	assertEquals(0, jdbcTemplate.queryForInt("select count(*) from ConnectedAccount where member = 1 and provider = 'tripit'"));
    	accountRepository.connectAccount(1L, "tripit", "accessToken", "cwalls");
    	assertEquals(1, jdbcTemplate.queryForInt("select count(*) from ConnectedAccount where member = 1 and provider = 'tripit'"));
    	assertExpectedAccount(accountRepository.findByConnectedAccount("tripit", "accessToken"));
    }

    @Test(expected=AccountAlreadyConnectedException.class)
    public void accountAlreadyConnected() throws Exception {
    	accountRepository.connectAccount(1L, "facebook", "accessToken", "cwalls");
    }

    @Test
    public void findConnectedAccount() throws Exception {
    	assertExpectedAccount(accountRepository.findByConnectedAccount("facebook", "accesstoken"));
    }

    @Test(expected=ConnectedAccountNotFoundException.class)
    public void connectedAccountNotFound() throws Exception {
    	accountRepository.findByConnectedAccount("badtoken", "facebook");
    }

    @Test
    public void disconnectAccount() {
    	assertEquals(1, jdbcTemplate.queryForInt("select count(*) from ConnectedAccount where member = 1 and provider = 'facebook'"));
    	accountRepository.disconnectAccount(1L, "facebook");
    	assertEquals(0, jdbcTemplate.queryForInt("select count(*) from ConnectedAccount where member = 1 and provider = 'facebook'"));
    }
    
    @Test
    public void findFriendAccounts() throws Exception {
    	List<Account> accounts = accountRepository.findFriendAccounts("facebook", Collections.singletonList("1"));
    	assertEquals(1, accounts.size());
    	assertExpectedAccount(accounts.get(0));
    }
        
    @Test
    public void isConnected() {
    	assertTrue(accountRepository.hasConnectedAccount(1L, "facebook"));
    }

    @Test
    public void notConnected() {
    	assertFalse(accountRepository.hasConnectedAccount(1L, "tripit"));
    }
    
    @Test
    public void connectApp() throws InvalidApiKeyException {
    	ConnectedApp app = accountRepository.connectApp(1L, "123456789");
    	assertEquals("123456789", app.getApiKey());
    	assertEquals((Long) 1L, app.getAccountId());
    	assertNotNull(app.getAccessToken());
    	assertNotNull(app.getSecret());
    }

    @Test(expected=InvalidApiKeyException.class)
    public void connectAppInvalidApiKey() throws InvalidApiKeyException {
    	accountRepository.connectApp(1L, "invalidApiKey");
    }

    @Test
    public void findConnectedApp() throws ConnectedAppNotFoundException {
    	ConnectedApp connection = accountRepository.findConnectedApp("234567890");
    	assertEquals((Long) 1L, connection.getAccountId());
    	assertEquals("123456789", connection.getApiKey());
    	assertEquals("234567890", connection.getAccessToken());
    	assertEquals("345678901", connection.getSecret());
    }

	private void assertExpectedAccount(Account account) {
	    assertEquals("Craig", account.getFirstName());
    	assertEquals("Walls", account.getLastName());
    	assertEquals("Craig Walls", account.getFullName());
    	assertEquals("cwalls@vmware.com", account.getEmail());
    	assertEquals("habuma", account.getUsername());
    	assertEquals("http://localhost:8080/members/habuma", account.getProfileUrl());
    	assertEquals("http://localhost:8080/resources/profile-pics/male/small.jpg", account.getPictureUrl());
    }
	
	@Rule
	public TransactionalMethodRule transactional = new TransactionalMethodRule();

}
