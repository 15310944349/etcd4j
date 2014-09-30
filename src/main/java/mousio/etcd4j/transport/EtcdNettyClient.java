package mousio.etcd4j.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import mousio.client.ConnectionState;
import mousio.client.retry.RetryHandler;
import mousio.etcd4j.promises.EtcdResponsePromise;
import mousio.etcd4j.requests.EtcdKeyRequest;
import mousio.etcd4j.requests.EtcdRequest;
import mousio.etcd4j.requests.EtcdVersionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Map;

/**
 * Netty client for the requests and responses
 */
public class EtcdNettyClient implements EtcdClientImpl {
  private static final Logger logger = LoggerFactory.getLogger(EtcdNettyClient.class);

  private final Bootstrap bootstrap;
  private final NioEventLoopGroup eventLoopGroup;

  private final URI[] uris;
  protected int lastWorkingUriIndex = 0;

  /**
   * Constructor
   *
   * @param sslContext SSL context if connecting with SSL. Null if not connecting with SSL.
   * @param uri        to connect to
   */
  public EtcdNettyClient(final SslContext sslContext, final URI... uri) {
    this.eventLoopGroup = new NioEventLoopGroup();

    this.uris = uri;

    // Configure the client.
    this.bootstrap = new Bootstrap();
    bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 300)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline p = ch.pipeline();
            if (sslContext != null) {
              p.addLast(sslContext.newHandler(ch.alloc()));
            }
            p.addLast("codec", new HttpClientCodec());
            p.addLast("aggregate", new HttpObjectAggregator(1024 * 100));
          }
        });
  }

  /**
   * Send a request and get a future.
   *
   * @param etcdRequest Etcd Request to send
   * @return Promise for the request.
   */
  public <R> EtcdResponsePromise<R> send(final EtcdRequest<R> etcdRequest) throws IOException {
    final ConnectionState connectionState = new ConnectionState(uris);
    connectionState.uriIndex = lastWorkingUriIndex;

    if (etcdRequest.getPromise() == null) {
      EtcdResponsePromise<R> responsePromise = new EtcdResponsePromise<>(etcdRequest.getRetryPolicy(), connectionState, new RetryHandler() {
        @Override public void doRetry() throws IOException {
          connect(etcdRequest, connectionState);
        }
      });
      etcdRequest.setPromise(responsePromise);
    }

    connectionState.startTime = new Date().getTime();
    connect(etcdRequest, connectionState);

    return etcdRequest.getPromise();
  }

  /**
   * Connect to server
   *
   * @param etcdRequest to request with
   * @param <R>         Type of response
   * @throws IOException if request could not be sent.
   */
  @SuppressWarnings("unchecked")
  protected <R> void connect(final EtcdRequest<R> etcdRequest) throws IOException {
    this.connect(etcdRequest, etcdRequest.getPromise().getConnectionState());
  }

  /**
   * Connect to server
   *
   * @param etcdRequest     to request with
   * @param connectionState for retries
   * @param <R>             Type of response
   * @throws IOException if request could not be sent.
   */
  @SuppressWarnings("unchecked")
  protected <R> void connect(final EtcdRequest<R> etcdRequest, final ConnectionState connectionState)
      throws IOException {
    // Start the connection attempt.
    final ChannelFuture connectFuture = bootstrap.clone()
        .connect(uris[connectionState.uriIndex].getHost(), uris[connectionState.uriIndex].getPort());

    final Channel channel = connectFuture.channel();
    etcdRequest.getPromise().attachNettyPromise(
        (Promise<R>) new DefaultPromise<>(connectFuture.channel().eventLoop())
    );

    connectFuture.addListener(new GenericFutureListener<ChannelFuture>() {
      @Override
      public void operationComplete(ChannelFuture f) throws Exception {
        if (!f.isSuccess()) {
          etcdRequest.getPromise().handleRetry(f.cause());
          return;
        }

        logger.info("Connected to " + channel.remoteAddress().toString());

        lastWorkingUriIndex = connectionState.uriIndex;

        modifyPipeLine(etcdRequest, f.channel().pipeline());

        HttpRequest httpRequest = createHttpRequest(etcdRequest.getUrl(), etcdRequest);

        // send request
        channel.writeAndFlush(httpRequest);
      }
    });
  }

  /**
   * Modify the pipeline for the request
   *
   * @param req      to process
   * @param pipeline to modify
   * @param <R>      Type of Response
   */
  @SuppressWarnings("unchecked")
  private <R> void modifyPipeLine(final EtcdRequest<R> req, final ChannelPipeline pipeline) {
    if (req.getTimeout() != -1) {
      pipeline.addFirst(new ReadTimeoutHandler(req.getTimeout(), req.getTimeoutUnit()));
    }

    if (req instanceof EtcdKeyRequest) {
      pipeline.addLast(
          new EtcdKeyResponseHandler(this, (EtcdKeyRequest) req)
      );
    } else if (req instanceof EtcdVersionRequest) {
      pipeline.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
          (((EtcdVersionRequest) req).getPromise()).getNettyPromise()
              .setSuccess(
                  msg.content().toString(Charset.defaultCharset()));
        }
      });
    } else {
      throw new RuntimeException("Unknown request type " + req.getClass().getName());
    }

    pipeline.addLast(new ChannelHandlerAdapter() {
      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        req.getPromise().getNettyPromise().setFailure(cause);
      }
    });
  }

  /**
   * Get HttpRequest belonging to etcdRequest
   *
   * @param uri         to send request to
   * @param etcdRequest to send
   * @param <R>         Response type
   * @return HttpRequest
   * @throws IOException if request could not be created
   */
  public static <R> HttpRequest createHttpRequest(String uri, EtcdRequest<R> etcdRequest) throws IOException {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, etcdRequest.getMethod(), uri);
    httpRequest.headers().add("Connection", "keep-alive");
    try {
      httpRequest = setRequestParameters(uri, etcdRequest, httpRequest);
    } catch (Exception e) {
      throw new IOException(e);
    }
    return httpRequest;
  }

  /**
   * Set parameters on request
   *
   * @param uri         to connect to
   * @param etcdRequest to send
   * @param httpRequest to send
   * @return Http Request
   * @throws Exception on fail
   */
  private static HttpRequest setRequestParameters(String uri, EtcdRequest<?> etcdRequest, HttpRequest httpRequest) throws Exception {
    // Set possible key value pairs
    Map<String, String> keyValuePairs = etcdRequest.getRequestParams();
    if (keyValuePairs != null && !keyValuePairs.isEmpty()) {
      if (etcdRequest.getMethod() == HttpMethod.POST) {
        HttpPostRequestEncoder bodyRequestEncoder = new HttpPostRequestEncoder(httpRequest, false);
        for (Map.Entry<String, String> entry : keyValuePairs.entrySet()) {
          bodyRequestEncoder.addBodyAttribute(entry.getKey(), entry.getValue());
        }

        httpRequest = bodyRequestEncoder.finalizeRequest();
        bodyRequestEncoder.close();
      } else {
        String getLocation = "";
        for (Map.Entry<String, String> entry : keyValuePairs.entrySet()) {
          if (!getLocation.isEmpty()) {
            getLocation += "&";
          }
          getLocation += entry.getKey() + "=" + entry.getValue();
        }

        if (!uri.contains("?")) {
          httpRequest.setUri(uri.concat("?").concat(getLocation));
        } else {
          httpRequest.setUri(uri);
        }
      }
    }
    etcdRequest.setHttpRequest(httpRequest);
    return httpRequest;
  }

  /**
   * Close netty
   */
  public void close() {
    eventLoopGroup.shutdownGracefully();
  }
}