package ru.carabi.server.soap;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiProduct;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Получение и запись сведений о продукции Караби.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "ProductionService")
public class ProductionService {
	private static final Logger logger = CarabiLogging.getLogger(ProductionService.class);
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	@EJB
	private UsersControllerBean usersController;
	
	/**
	 * Получение сведений о версиях продукта.
	 * Выдаются подробные сведения о всех версиях одного продукта компании Караби по системному имени.
	 * @param token авторизационный токен
	 * @param productName системное имя продукта
	 * @return массив сведений о версиях
	 * @throws CarabiException если продукта с таким именем не существует
	 */
	@WebMethod(operationName = "checkVersion")
	public List<ProductVersion> checkVersion(
			@WebParam(name = "token") String token,
			@WebParam(name = "productName") String productName
	) throws CarabiException {
		usersController.tokenControl(token);
		TypedQuery<CarabiProduct> jpaQuery = em.createNamedQuery("findCarabiProduct", CarabiProduct.class);
		jpaQuery.setParameter("productName", productName);
		try {
			CarabiProduct product = jpaQuery.getSingleResult();
			List<ProductVersion> versions = product.getVersions();
			TreeSet<ProductVersion> orderer = new TreeSet(new VersionComparator());
			for (ProductVersion version: versions) {
				orderer.add(version);
			}
			versions.clear();
			for (ProductVersion version: orderer) {
				version.setCarabiProduct(null);
				versions.add(version);
			}
			return versions;
		} catch(NoResultException e) {
			throw new CarabiException("No product \""+productName + "\"");
		}
	}
	
	/**
	 * Получение сведений о последней версии продукта.
	 * Выдаются подробные сведения о последней версии одного продукта компании Караби по системному имени.
	 * @param token авторизационный токен
	 * @param productName системное имя продукта
	 * @return сведения о последней версии
	 * @throws CarabiException если продукта с таким именем не существует
	 */
	@WebMethod(operationName = "checkLastVersion")
	public ProductVersion checkLastVersion(
			@WebParam(name = "token") String token,
			@WebParam(name = "productName") String productName
	) throws CarabiException {
		List<ProductVersion> checkVersion = checkVersion(token, productName);
		return checkVersion.get(checkVersion.size()-1);
	}
	
	private static int[] parseVersion(String version) {
		logger.info(version);
		String[] elements = version.split("\\.");
		int[] numbers = new int[elements.length];
		int i = 0;
		for (String element: elements) {
			try {
				numbers[i] = Integer.valueOf(element);
				i++;
			} catch (NumberFormatException e) {
				logger.log(Level.WARNING, "parse error", e);
				numbers[i] = 0;
			}
		}
		return numbers;
	}
	
	private static class VersionComparator implements Comparator<ProductVersion> {
		@Override
		public int compare(ProductVersion t, ProductVersion t1) {
			String version = t.getVersionNumber();
			String version1 = t1.getVersionNumber();
			int[] numbers = parseVersion(version);
			int[] numbers1 = parseVersion(version1);
			for (int i=0; i<Math.min(numbers.length, numbers1.length); i++) {
				if (numbers[i] != numbers1[i]) {
					return numbers[i] - numbers1[i];
				}
			}
			if (numbers.length != numbers1.length) { 
				return numbers.length - numbers1.length;
			} else {
				return 0;
			}
		}
	}
}
