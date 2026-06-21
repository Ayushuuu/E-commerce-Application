package com.ecommerce.project.service;

import com.ecommerce.project.exception.APIException;
import com.ecommerce.project.exception.ResourceNotFoundException;
import com.ecommerce.project.model.Cart;
import com.ecommerce.project.model.CartItem;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.payload.CartDTO;
import com.ecommerce.project.payload.ProductDTO;
import com.ecommerce.project.repository.CartItemRepository;
import com.ecommerce.project.repository.CartRepository;
import com.ecommerce.project.repository.ProductRepository;
import com.ecommerce.project.util.AuthUtil;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CartServiceImpl implements CartService{

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Override
    public CartDTO addProductToCart(Long productId, Integer quantity) {
        Cart cart = createCart();

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "Product Id", productId));

        validationCheck(cart, product, productId, quantity);

        CartItem newCartItem = createNewCartItem(cart, product, quantity);


        cart.setTotalPrice(cart.getTotalPrice() + (newCartItem.getPrice() * quantity));
        cart.getCartItems().add(newCartItem);
        cartRepository.save(cart);

        CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);

        List<CartItem> cartItems = cart.getCartItems();

        Stream<ProductDTO> productDTOStream = cartItems.stream().map(item ->{
            ProductDTO map = modelMapper.map(item.getProduct(), ProductDTO.class);
            map.setQuantity(item.getQuantity());
            return map;
        });

        cartDTO.setProducts(productDTOStream.toList());

    return cartDTO;
    }

    @Override
    public List<CartDTO> getAllCarts() {
        List<Cart> carts = cartRepository.findAll();
        if(carts.isEmpty()){
            throw new APIException("No cart exists.");
        }
        List<CartDTO> cartDTOS = carts
                .stream()
                .map(cart -> {
                    CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
                    cart.getCartItems().forEach(cartItem -> cartItem.getProduct().setQuantity(cartItem.getQuantity()));
                    List<ProductDTO> productDTOS = cart.getCartItems().stream()
                            .map(cartItem -> modelMapper.map(cartItem.getProduct(), ProductDTO.class))
                            .collect(Collectors.toList());
                    cartDTO.setProducts(productDTOS);
                    return cartDTO;
                }).toList();
        return cartDTOS;
    }

    @Override
    public CartDTO getCart() {
        String emailId = authUtil.loggedInEmail();
        Cart userCart = cartRepository.findCartByEmail(emailId);
        Long cartId = userCart.getCartId();
        Cart cart = cartRepository.findCartByEmailAndCartId(emailId, cartId);
        if(cart == null){
            throw new ResourceNotFoundException("cart", "cartId", cartId);
        }
        CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
        cart.getCartItems().forEach(cartItem -> cartItem.getProduct().setQuantity(cartItem.getQuantity()));
        List<ProductDTO> productDTOS = cart
                .getCartItems()
                .stream()
                .map(cartItem -> modelMapper.map(cartItem.getProduct(), ProductDTO.class))
                .toList();
        cartDTO.setProducts(productDTOS);
        return cartDTO;
    }

    @Transactional
    @Override
    public CartDTO updateProductQuantityInCart(Long productId, Integer quantity) {
        String emailId = authUtil.loggedInEmail();
        Cart userCart = cartRepository.findCartByEmail(emailId);
        Long cartId = userCart.getCartId();

        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("cart", "cartId", cartId));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "Product Id", productId));

        if(product.getQuantity() == 0){
            throw new APIException(product.getProductName() + " is Out of Stock!");
        }

        if(product.getQuantity() < quantity){
            throw new APIException("Please make an order of the Product " + product.getProductName() + " less than or equal to " + product.getQuantity());
        }

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cartId, productId);

        if(cartItem == null){
            throw new APIException("Product " + product.getProductName() + " doesn't exist!");
        }

        // Calculate new quantity
        int newQuantity = cartItem.getQuantity() + quantity;

        // Validation to prevent negative quantities
        if (newQuantity < 0) {
            throw new APIException("The resulting quantity cannot be negative.");
        }

        if (newQuantity == 0){
            deleteProductFromCart(cartId, productId);
        } else {
            cartItem.setPrice(product.getSpecialPrice());
            cartItem.setQuantity(cartItem.getQuantity() + quantity);
            cartItem.setDiscount(product.getDiscount());
            cart.setTotalPrice(cart.getTotalPrice() + (cartItem.getPrice() * quantity));
            cartRepository.save(cart);
        }
        CartItem updatedItem = cartItemRepository.save(cartItem);

        if(updatedItem.getQuantity() == 0){
            cartItemRepository.deleteById(updatedItem.getCartItemId());
        }

        CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
        List<CartItem> cartItems = cart.getCartItems();

        List<ProductDTO> productDTOS = cartItems
                .stream()
                .map(tempCartItem -> {
                    ProductDTO prd = modelMapper.map(tempCartItem.getProduct(), ProductDTO.class);
                    prd.setQuantity(tempCartItem.getQuantity());
                    return prd;
                })
                .toList();
        cartDTO.setProducts(productDTOS);

        return cartDTO;
    }

    @Transactional
    @Override
    public String deleteProductFromCart(Long cartId, Long productId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("cart", "cartId", cartId));

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cartId, productId);

        if (cartItem == null) {
            throw new ResourceNotFoundException("CartItem", "CartId", cartId);
        }

        cart.setTotalPrice(cart.getTotalPrice() - (cartItem.getPrice() * cartItem.getQuantity()));
        cartItemRepository.deleteCartItemByProductIdAndCartId(cartId, productId);

        return "Product " + cartItem.getProduct().getProductName() + " has been removed from the cart!";
    }

    @Override
    public void updateProductInCarts(Long cartId, Long productId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("cart", "cartId", cartId));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "Product Id", productId));

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cartId, productId);

        if(cartItem == null){
            throw new APIException("Product" + product.getProductName() + " not available in the cart!");
        }

        double cartPrice = cart.getTotalPrice() - (cartItem.getPrice() * cartItem.getQuantity());

        cartItem.setPrice(product.getSpecialPrice());

        cart.setTotalPrice(cartPrice + (cartItem.getPrice() * cartItem.getQuantity()));

        cartItem = cartItemRepository.save(cartItem);


    }

    public Cart createCart(){
        Cart userCart = cartRepository.findCartByEmail(authUtil.loggedInEmail());
        if (userCart != null) {
            return userCart;
        }

        Cart cart = new Cart();
        cart.setUser(authUtil.loggedInUser());
        return cartRepository.save(cart);
    }

    private void validationCheck(Cart cart, Product product,Long productId, Integer quantity) {
        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cart.getCartId(), productId);

        if(cartItem != null){
            throw new APIException("Product: " + product.getProductName() + " already exists inside Cart!");
        }

        if(product.getQuantity() == 0){
            throw new APIException(product.getProductName() + " is Out of Stock!");
        }

        if(product.getQuantity() < quantity){
            throw new APIException("Please make an order of the Product " + product.getProductName() + " less than or equal to " + product.getQuantity());
        }
    }

    private CartItem createNewCartItem(Cart cart, Product product, Integer quantity) {
        CartItem newCartItem = new CartItem();
        newCartItem.setCart(cart);
        newCartItem.setProduct(product);
        newCartItem.setQuantity(quantity);
        newCartItem.setDiscount(product.getDiscount());
        newCartItem.setPrice(product.getSpecialPrice());
        return cartItemRepository.save(newCartItem);
    }
}
